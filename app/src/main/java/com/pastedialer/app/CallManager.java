package com.pastedialer.app;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.widget.Toast;

import java.util.ArrayList;

/** চলমান কলগুলোর একটি ছোট স্টেট-হোল্ডার (সবকিছু main thread এ চলে)। */
@TargetApi(Build.VERSION_CODES.M)
final class CallManager {

    interface Listener {
        void onChanged();
    }

    private static final ArrayList<Call> calls = new ArrayList<>();
    private static CallService service;
    private static Context app;
    private static Listener listener;
    private static boolean muted;
    private static int route = CallAudioState.ROUTE_EARPIECE;

    private CallManager() {}

    private static final Call.Callback CB = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            handleState(call, state);
            notifyChanged();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            notifyChanged();
        }
    };

    // ------------------------------------------------------------ lifecycle
    static void add(CallService s, Call c) {
        service = s;
        app = s.getApplicationContext();
        calls.add(c);
        c.registerCallback(CB);
        handleState(c, c.getState());
        notifyChanged();
    }

    static void remove(Call c) {
        c.unregisterCallback(CB);
        calls.remove(c);
        if (!hasLive()) stopAndNotify();
        notifyChanged();
    }

    static void serviceGone(CallService s) {
        if (service == s) service = null;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static void notifyChanged() {
        if (listener != null) listener.onChanged();
    }

    static void audioChanged(CallAudioState s) {
        if (s != null) {
            muted = s.isMuted();
            route = s.getRoute();
        }
        notifyChanged();
    }

    // ---------------------------------------------------------------- state
    private static void handleState(Call c, int state) {
        if (state == Call.STATE_ACTIVE && app != null
                && Prefs.isAuto(app) && !CallRecorder.isRecording()) {
            startRecording();
        } else if (state == Call.STATE_DISCONNECTED && !hasLive()) {
            stopAndNotify();
        }
    }

    private static boolean hasLive() {
        for (Call c : calls) {
            if (c.getState() != Call.STATE_DISCONNECTED) return true;
        }
        return false;
    }

    /** রিংিং কল থাকলে সেটি, নাহলে সর্বশেষ জীবিত কল। */
    static Call current() {
        if (calls.isEmpty()) return null;
        for (Call c : calls) {
            if (c.getState() == Call.STATE_RINGING) return c;
        }
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).getState() != Call.STATE_DISCONNECTED) return calls.get(i);
        }
        return calls.get(calls.size() - 1);
    }

    static String number(Call c) {
        if (c == null) return "";
        Call.Details d = c.getDetails();
        if (d == null) return "";
        Uri h = d.getHandle();
        if (h == null) return "";
        String s = h.getSchemeSpecificPart();
        return s == null ? "" : Uri.decode(s);
    }

    // -------------------------------------------------------------- actions
    static void answer() {
        Call c = current();
        if (c != null) c.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    static void rejectOrHangup() {
        Call c = current();
        if (c == null) return;
        if (c.getState() == Call.STATE_RINGING) c.reject(false, null);
        else c.disconnect();
    }

    static boolean isMuted() {
        return muted;
    }

    static boolean isSpeaker() {
        return route == CallAudioState.ROUTE_SPEAKER;
    }

    static void toggleMute() {
        if (service != null) service.setMuted(!muted);
    }

    static void toggleSpeaker() {
        if (service != null) {
            service.setAudioRoute(isSpeaker()
                    ? CallAudioState.ROUTE_WIRED_OR_EARPIECE
                    : CallAudioState.ROUTE_SPEAKER);
        }
    }

    // ------------------------------------------------------------ recording
    static void toggleRecording() {
        if (CallRecorder.isRecording()) stopAndNotify();
        else startRecording();
    }

    static void startRecording() {
        if (app == null) return;
        String err = CallRecorder.start(app, number(current()));
        if (err != null) {
            toast(err);
        } else if (CallRecorder.usingMic()) {
            toast("রেকর্ডিং শুরু (মাইক মোড)। দুই পাশের কণ্ঠ রেকর্ডের জন্য স্পিকার অন করুন।");
        } else {
            toast("রেকর্ডিং শুরু হয়েছে");
        }
        notifyChanged();
    }

    static void stopAndNotify() {
        if (!CallRecorder.isRecording()) return;
        String saved = CallRecorder.stop();
        if (saved != null) toast("রেকর্ডিং সেভ হয়েছে: " + saved);
        else toast("রেকর্ডিং সেভ হয়নি (কোনো অডিও পাওয়া যায়নি)");
        notifyChanged();
    }

    private static void toast(String m) {
        if (app != null) Toast.makeText(app, m, Toast.LENGTH_LONG).show();
    }
}
