package de.evil2000.standheizung;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * This tiny class receives the broadcast intents and starts the service with it.
 */
public class SMSReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context,SmsReceiverService.class);
        i.setAction(intent.getAction());
        i.putExtras(intent);
        context.startService(i);
    }

}