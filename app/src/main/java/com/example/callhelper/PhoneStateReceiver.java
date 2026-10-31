package com.example.callhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/**
 * يحدّث حالة المكالمة، ويمرّر رقم المتصل إلى الخدمة (إذا كانت شغّالة) لقراءة اسمه.
 * لا يشغّل الخدمة من الخلفية (ممنوع لخدمة الميكروفون على أندرويد 11+).
 */
public class PhoneStateReceiver extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            CallController.setCallState(TelephonyManager.CALL_STATE_RINGING);
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (number != null && !number.isEmpty() && VoiceCommandService.isRunning()) {
                try {
                    Intent i = new Intent(context, VoiceCommandService.class);
                    i.setAction(VoiceCommandService.ACTION_ANNOUNCE);
                    i.putExtra(VoiceCommandService.EXTRA_NUMBER, number);
                    context.startService(i);
                } catch (Exception ignored) {}
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            CallController.setCallState(TelephonyManager.CALL_STATE_OFFHOOK);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            CallController.setCallState(TelephonyManager.CALL_STATE_IDLE);
        }
    }
}
