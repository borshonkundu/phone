package com.pastedialer.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

@TargetApi(Build.VERSION_CODES.M)
public class InCallActivity extends Activity implements CallManager.Listener {

    private TextView tvName, tvNumber, tvState, tvTimer;
    private Button btnMute, btnSpeaker, btnRec, btnAnswer, btnEnd;
    private View rowControls;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String cachedNumber;
    private String cachedName;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshTimer();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_incall);
        tvName = findViewById(R.id.tvName);
        tvNumber = findViewById(R.id.tvNumber);
        tvState = findViewById(R.id.tvState);
        tvTimer = findViewById(R.id.tvTimer);
        btnMute = findViewById(R.id.btnMute);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnRec = findViewById(R.id.btnRec);
        btnAnswer = findViewById(R.id.btnAnswer);
        btnEnd = findViewById(R.id.btnEnd);
        rowControls = findViewById(R.id.rowControls);

        btnAnswer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CallManager.answer(); }
        });
        btnEnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CallManager.rejectOrHangup(); }
        });
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CallManager.toggleMute(); }
        });
        btnSpeaker.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CallManager.toggleSpeaker(); }
        });
        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CallManager.toggleRecording(); }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallManager.setListener(this);
        refresh();
        h.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        CallManager.setListener(null);
        h.removeCallbacks(tick);
    }

    @Override
    public void onChanged() {
        refresh();
    }

    @Override
    public void onBackPressed() {
        // কল চলাকালীন ব্যাক চাপলে অ্যাক্টিভিটি লুকাবে, কল কাটবে না
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ UI
    private void refresh() {
        Call c = CallManager.current();
        if (c == null) {
            finish();
            return;
        }
        String num = CallManager.number(c);
        if (!num.equals(cachedNumber)) {
            cachedNumber = num;
            cachedName = lookupName(num);
        }
        tvName.setText(cachedName != null ? cachedName : (num.isEmpty() ? "অজানা নম্বর" : num));
        tvNumber.setText(num);

        int st = c.getState();
        tvState.setText(stateText(st));

        boolean ringing = st == Call.STATE_RINGING;
        btnAnswer.setVisibility(ringing ? View.VISIBLE : View.GONE);
        rowControls.setVisibility(ringing ? View.GONE : View.VISIBLE);
        btnEnd.setText(ringing ? "প্রত্যাখ্যান" : "কল কেটে দিন");

        btnMute.setText(CallManager.isMuted() ? "মিউট বন্ধ" : "মিউট");
        btnSpeaker.setText(CallManager.isSpeaker() ? "স্পিকার বন্ধ" : "স্পিকার");
        btnRec.setText(CallRecorder.isRecording() ? "রেকর্ডিং বন্ধ" : "রেকর্ড");
        refreshTimer();
    }

    private void refreshTimer() {
        Call c = CallManager.current();
        if (c == null) return;
        int st = c.getState();
        Call.Details d = c.getDetails();
        long ct = d == null ? 0 : d.getConnectTimeMillis();
        if ((st == Call.STATE_ACTIVE || st == Call.STATE_HOLDING) && ct > 0) {
            long s = Math.max(0, (System.currentTimeMillis() - ct) / 1000);
            tvTimer.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
        } else {
            tvTimer.setText("");
        }
    }

    private String stateText(int st) {
        switch (st) {
            case Call.STATE_RINGING:       return "ইনকামিং কল...";
            case Call.STATE_DIALING:       return "ডায়াল হচ্ছে...";
            case Call.STATE_CONNECTING:    return "সংযোগ হচ্ছে...";
            case Call.STATE_ACTIVE:        return "কথা হচ্ছে";
            case Call.STATE_HOLDING:       return "হোল্ডে আছে";
            case Call.STATE_DISCONNECTING: return "কল শেষ হচ্ছে...";
            case Call.STATE_DISCONNECTED:  return "কল শেষ";
            default:                       return "";
        }
    }

    private String lookupName(String num) {
        if (num == null || num.isEmpty()) return null;
        if (checkCallingOrSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return null;
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num));
            c = getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
