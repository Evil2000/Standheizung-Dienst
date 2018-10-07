package de.evil2000.standheizung;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * This service should be invoked by the SMSReceiver (BroadcastReceiver) which gets called when a
 * SMS is received.
 * This service is also invoked by the start of the main activity and should restart itself if
 * killed by sending a broadcast intent to itself.
 */
public class SmsReceiverService extends Service {
    private SMSReceiver smsBroadcastReceiver;
    private SharedPreferences settings;
    private BluetoothSocket rfcommSocket;
    private InputStream btIn;
    private OutputStream btOut;

    /**
     * Service is created. Start a thread which infinitely tries to reconnect to the bluetooth
     * device. This os to make sure no other (evil) device can connect to the btDevice.
     * Then register the SMSReceiver class with the SMS_RECEIVED broadcast intent.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // Run a thread which tries every 10 seconds to connect to btDevice forever. Keep in mind
        // that a connection timeout may occur after 5 to 7 seconds which is independent from timer.
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                connectWithBtDevice();
            }
        },0,10000);

        // Register the broadcast SMS receiver (see SMSReceiver.java)
        smsBroadcastReceiver = new SMSReceiver();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mIntentFilter.addAction("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(smsBroadcastReceiver, mIntentFilter);
    }

    /**
     * Service got a start command. In contrast to onCreate this method can be called more then
     * once with or without an intent.
     *
     * @param intent The intent received
     * @param flags ???
     * @param startId A start counter.
     * @return START_STICKY
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(Hlpr.__FUNC__(getClass()),
                "SmsReceiverService started with startId=" + startId + " flags=" + flags +
                        " Intent: " + intent);

        // If an SMS intent is received, handle the message.
        if (intent != null && intent.getAction() != null &&
                intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED"))
            handleSms(intent);

        // Tell android not to kill our service (please!).
        return START_STICKY;
    }

    /**
     * Service will be shut down. Close all sockets and unregister the broadcast receiver. Then
     * send a broadcast intent to ourselves so that the service gets hopefully restarted.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        // If service gets killed by android you may leave the smsBroadcastReceiver registered.
        // Otherwise the service will not be restarted on reception of a SMS.
        unregisterReceiver(smsBroadcastReceiver);

        // Close all bluetooth rfcomm sockets and streams.
        try {
            btIn.close();
            btOut.close();
            rfcommSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // But to be sure our service is restarted after a kill, send a restart broadcast
        sendBroadcast(new Intent("de.evil2000.standheizung.RestartService"));
    }

    /**
     * If an activity tries to bind to this service, we throw an exception (Maybe better to
     * return nothing?)
     *
     * @param intent
     * @return
     */
    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    /**
     * Handle the incoming SMS by parsing the intent and extracting the neccesary information and
     * sending the right commands to the btDevice.
     * SMS commands (message text) currently supported:
     * "AH on":
     * Turn auxiliary heating on by sending A4 (relay 1 close) and A3 (rly 1 open) to btDevice.
     * "AH off":
     * Turn auxiliary heating on by sending B4 (relay 2 close) and B3 (rly 2 open) to btDevice.
     * "AH anlernen1":
     * Send special relay open/close combination to the btDevice for the AH to learn the T93 remote.
     * "AH anlernen2":
     * Send special relay open/close combination to the btDevice for the AH to learn the T95 remote.
     *
     * @param intent SMS_RECEIVED intent only!
     */
    private void handleSms(Intent intent) {
        // Read the SMS from the intent.
        Bundle pudsBundle = intent.getExtras();
        Object[] pdus = (Object[]) pudsBundle.get("pdus");
        SmsMessage messages = SmsMessage.createFromPdu((byte[]) pdus[0]);

        // @formatter:off
        Log.i(Hlpr.__FUNC__(getClass()), "Received SMS message:\n"
                + "From    : " + messages.getOriginatingAddress() + "\n"
                + "ICCidx  : " + messages.getIndexOnIcc() + "\n"
                + "Prot-ID : " + messages.getProtocolIdentifier() + "\n"
                + "Subject : " + messages.getPseudoSubject() + "\n"
                + "SMSC    : " + messages.getServiceCenterAddress() + "\n"
                + "Status  : " + messages.getStatus() + "\n"
                + "ICC-Stat: " + messages.getStatusOnIcc() + "\n"
                + "Timestmp: " + messages.getTimestampMillis() + "\n"
                + "Message : " + messages.getMessageBody() + "\n"
        );
        // @formatter:on

        // Check if message start with "AH" and is received from a trusted sender.
        if (!(messages.getMessageBody().startsWith("AH") && PhoneNumberUtils
                .compare(messages.getOriginatingAddress(),
                        settings.getString("smsAuthSender", "")))) {
            Log.d(Hlpr.__FUNC__(getClass()),
                    "Sender " + messages.getOriginatingAddress() + " not allowed to drive AH! " +
                            "(!= " + settings.getString("smsAuthSender", "") + ")");
            return;
        }

        // Split the SMS message by space and treat the second argument as "command"
        String command;
        try {
            command = messages.getMessageBody().split(" ")[1];
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.w(Hlpr.__FUNC__(getClass()),"SMS message is not in format 'AH " +
                    "(on|off|anlernen1|anlernen2)'! Ignoring.");
            return;
        }

        // Send the right open/close commands to btDevice if on/off/anlernenX is received.
        if (command.toLowerCase().equals("on")) {
            if (!sendToBt(Hlpr.relayCh1Close)) return;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                sendSms(getString(R.string.ah_on));
            }
        } else if (command.toLowerCase().equals("off")) {
            if (!sendToBt(Hlpr.relayCh2Close)) return;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh2Open)) return;
                sendSms(getString(R.string.ah_off));
            }
        } else if (command.toLowerCase().equals("anlernen1")) {
            try {
                if (!sendToBt(Hlpr.relayCh2Close)) return;

                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh2Open)) return;
                sendSms(getString(R.string.remote_lern1_sent));
            }
        } else if (command.toLowerCase().equals("anlernen2")) {
            try {
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                if (!sendToBt(Hlpr.relayCh2Close)) return;
                Thread.sleep(3500);

                if (!sendToBt(Hlpr.relayCh1Open)) return;
                if (!sendToBt(Hlpr.relayCh2Open)) return;
                Thread.sleep(1000);

                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close)) return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open)) return;
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                sendSms(getString(R.string.remote_lern2_sent));
            }
        }
    }

    /**
     * Connect to the brDevice which is stored in the shared prefs. Catch the IOException if the
     * connection could not be established.
     *
     * @return True if a connection is established, false otherwise.
     */
    private boolean connectWithBtDevice() {
        if (rfcommSocket != null && rfcommSocket.isConnected()) return true;

        settings = getSharedPreferences(Hlpr.PREFS_NAME, Context.MODE_PRIVATE);
        String btDeviceAddress = settings.getString("btDeviceAddress", "");
        String btServiceUuid = settings.getString("btServiceUuid", Standheizung.publicRfcommUuid);

        if (btDeviceAddress == "" || btServiceUuid == "") {
            Log.w(Hlpr.__FUNC__(getClass()),
                    "No Bluetooth Device or Service UUID selected (btDeviceAddress: " +
                            btDeviceAddress + " btServiceUuid: " + btServiceUuid + ")");
            return false;
        }

        BluetoothAdapter btAdapter = Hlpr.getBluetoothAdapter();
        if (btAdapter == null) return false;

        BluetoothDevice btDevice = btAdapter.getRemoteDevice(btDeviceAddress);

        try {
            rfcommSocket =
                    btDevice.createRfcommSocketToServiceRecord(UUID.fromString(btServiceUuid));

            rfcommSocket.connect();

            btIn = rfcommSocket.getInputStream();
            btOut = rfcommSocket.getOutputStream();

        } catch (IOException e) {
            // Communication error occurred.
            Log.w(Hlpr.__FUNC__(getClass()),
                    "Communication error while trying to speak to BT " + "device " +
                            btDeviceAddress + ": "+e.getMessage());
            //e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Send a command to the connected btDevice. If the transmit fails, send a SMS to the trusted
     * sender to notify him of a connection error.
     *
     * @param b Byte to send
     * @return True if message was sent, false otherwise.
     */
    private boolean sendToBt(byte[] b) {
        try {
            if (!(rfcommSocket != null && rfcommSocket.isConnected())) {
                sendSms(getString(R.string.bt_not_connected));
                return false;
            }
            btOut.write(b);
            btOut.flush();
            return true;
        } catch (IOException e) {
            sendSms(getString(R.string.bt_error_sending) + e.getMessage());
            e.printStackTrace();
            try {
                btIn.close();
                btOut.close();
                rfcommSocket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        return false;
    }

    /**
     * Receive bytes from the connected btDevice.
     *
     * @return Bytes received
     */
    private byte[] readFromBt() {
        try {
            if (!(rfcommSocket != null && rfcommSocket.isConnected())) {
                sendSms(getString(R.string.bt_not_connected));
                return null;
            }
            byte[] b = null;
            btIn.read(b);
            return b;
        } catch (IOException e) {
            sendSms(getString(R.string.bt_error_receiving) + e.getMessage());
            e.printStackTrace();
            try {
                btIn.close();
                btOut.close();
                rfcommSocket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return null;
        }
    }

    /**
     * Send a SMS message to the trusted sender.
     *
     * @param message The message to send.
     */
    private void sendSms(String message) {
        sendSms(settings.getString("smsAuthSender", ""), message);
    }

    /**
     * Send a SMS message to a number.
     *
     * @param toNumber Receiver of the SMS message
     * @param message The message to send.
     */
    private void sendSms(String toNumber, String message) {
        SmsManager smsMgr = SmsManager.getDefault();
        smsMgr.sendTextMessage(toNumber, null, message, null, null);
    }
}
