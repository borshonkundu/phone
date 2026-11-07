package com.pastedialer.app;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

@TargetApi(Build.VERSION_CODES.M)
public class CallService extends InCallService {

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        CallManager.add(this, call);
        Intent i = new Intent(this, InCallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        CallManager.remove(call);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        CallManager.audioChanged(state);
    }

    @Override
    public void onDestroy() {
        CallManager.serviceGone(this);
        super.onDestroy();
    }
}
