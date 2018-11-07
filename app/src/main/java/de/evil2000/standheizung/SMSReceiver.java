package de.evil2000.standheizung;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;

/**
 * This tiny class receives the broadcast intents and starts the service with it.
 */
public class SMSReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences settings = context.getSharedPreferences(Hlpr.PREFS_NAME,Context.MODE_PRIVATE);
        TelephonyManager telMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        Intent i;

        if (settings.getBoolean("useMqttTransport",(telMgr.getSimState() == TelephonyManager.SIM_STATE_ABSENT))) {
            i = new Intent(context, MqttRecieverService.class);
        } else {
            i = new Intent(context, SmsReceiverService.class);
        }

        i.setAction(intent.getAction());
        i.putExtras(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

}