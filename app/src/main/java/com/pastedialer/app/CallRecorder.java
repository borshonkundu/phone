package com.pastedialer.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * কল রেকর্ডার।
 * - ইউজার SAF (ACTION_OPEN_DOCUMENT_TREE) দিয়ে SD কার্ডের ফোল্ডার সিলেক্ট করলে সেখানে সেভ হয়।
 * - সিলেক্ট না করলে SD কার্ডের অ্যাপ-নির্দিষ্ট ফোল্ডারে (Android/data/.../files/CallRecordings) সেভ হয়।
 * - ইন্টারনাল মেমোরিতে কখনো সেভ করে না।
 */
final class CallRecorder {

    private static MediaRecorder rec;
    private static ParcelFileDescriptor pfd;
    private static Uri docUri;
    private static File file;
    private static Context appCtx;
    private static boolean usingMic;
    private static String outName;

    private CallRecorder() {}

    static synchronized boolean isRecording() {
        return rec != null;
    }

    static synchronized boolean usingMic() {
        return usingMic;
    }

    /** সফল হলে null, ব্যর্থ হলে ত্রুটির বার্তা ফেরত দেয়। */
    static synchronized String start(Context ctx, String number) {
        if (rec != null) return null;
        appCtx = ctx.getApplicationContext();

        if (appCtx.checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return "RECORD_AUDIO পারমিশন দেওয়া হয়নি";
        }

        String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        String name = "Call_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + (digits.isEmpty() ? "" : "_" + digits) + ".m4a";

        try {
            Uri tree = Prefs.getTree(appCtx);
            if (tree != null) {
                ContentResolver cr = appCtx.getContentResolver();
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                docUri = DocumentsContract.createDocument(cr, parent, "audio/mp4", name);
                if (docUri == null) {
                    return "SD ফোল্ডারে ফাইল তৈরি করা যায়নি। আবার ফোল্ডার সিলেক্ট করুন।";
                }
                pfd = cr.openFileDescriptor(docUri, "rw");
                if (pfd == null) {
                    cleanup();
                    return "SD ফোল্ডারে ফাইল খোলা যায়নি";
                }
            } else {
                File dir = sdAppDir(appCtx);
                if (dir == null) {
                    return "SD কার্ড পাওয়া যায়নি। 'এসডি ফোল্ডার' বাটনে চেপে ফোল্ডার সিলেক্ট করুন।";
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    return "SD কার্ডে ফোল্ডার তৈরি করা যায়নি";
                }
                file = new File(dir, name);
            }
        } catch (Exception e) {
            cleanup();
            return "SD কার্ডে লেখা যাচ্ছে না: " + e.getMessage();
        }

        // প্রথমে কল-অডিও (দুই পাশ), না পারলে মাইক
        int[] sources = {MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            MediaRecorder r = new MediaRecorder();
            try {
                r.setAudioSource(src);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioChannels(1);
                r.setAudioSamplingRate(16000);
                r.setAudioEncodingBitRate(48000);
                if (pfd != null) r.setOutputFile(pfd.getFileDescriptor());
                else r.setOutputFile(file.getAbsolutePath());
                r.prepare();
                r.start();
                rec = r;
                usingMic = (src == MediaRecorder.AudioSource.MIC);
                outName = name;
                return null;
            } catch (Exception e) {
                try { r.reset(); } catch (Exception ignored) {}
                r.release();
            }
        }
        cleanup();
        return "রেকর্ডিং শুরু করা যায়নি (মাইক ব্যস্ত বা অনুমতি নেই)";
    }

    /** সেভ হওয়া ফাইলের নাম ফেরত দেয়; ব্যর্থ হলে null। */
    static synchronized String stop() {
        if (rec == null) return null;
        String name = outName;
        boolean ok = true;
        try {
            rec.stop();
        } catch (RuntimeException e) {
            ok = false; // খুব ছোট/খালি রেকর্ডিং
        }
        try { rec.release(); } catch (Exception ignored) {}
        rec = null;

        if (ok) {
            closePfd();
            docUri = null;
            file = null;
            return name;
        }
        cleanup();
        return null;
    }

    // -------------------------------------------------------------- helpers
    private static void closePfd() {
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }
    }

    private static void cleanup() {
        closePfd();
        try {
            if (docUri != null && appCtx != null) {
                DocumentsContract.deleteDocument(appCtx.getContentResolver(), docUri);
            }
        } catch (Exception ignored) {}
        try {
            if (file != null && file.exists()) //noinspection ResultOfMethodCallIgnored
                file.delete();
        } catch (Exception ignored) {}
        docUri = null;
        file = null;
    }

    /** রিমুভেবল (SD) স্টোরেজের অ্যাপ-নির্দিষ্ট ফোল্ডার; না পেলে null (ইন্টারনালে ফলব্যাক নেই)। */
    private static File sdAppDir(Context ctx) {
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) {
            for (int i = dirs.length - 1; i >= 1; i--) {
                if (dirs[i] != null) return new File(dirs[i], "CallRecordings");
            }
        }
        return null;
    }
}
