package de.evil2000.standheizung;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * This tiny class receives the broadcast intents and starts the service with it.
 */
public class SMSReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        TelephonyManager telMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        Intent i = null;
        if (telMgr.getSimState() == TelephonyManager.SIM_STATE_ABSENT) {
            i = new Intent(context,MqttRecieverService.class);
        } else {
            i = new Intent(context,SmsReceiverService.class);
        }
        i.setAction(intent.getAction());
        i.putExtras(intent);
        context.startService(i);
    }

}