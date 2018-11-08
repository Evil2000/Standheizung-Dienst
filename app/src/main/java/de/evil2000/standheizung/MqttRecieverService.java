package de.evil2000.standheizung;

import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class MqttRecieverService extends Service implements MqttCallbackExtended {
    private SharedPreferences settings;
    private BluetoothSocket rfcommSocket = null;
    private InputStream btIn = null;
    private OutputStream btOut = null;
    private MqttAndroidClient mqttClient = null;
    private String clientId = null;
    private String brokerURI = "";
    private final String topic_rx = "/AH/to";
    private final String topic_tx = "/AH/from";
    private Timer btConnectTimer = null;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, new Notification());
        }
        // Run a thread which tries every 10 seconds to connect to btDevice forever. Keep in mind
        // that a connection timeout may occur after 5 to 7 seconds which is independent from timer.
        btConnectTimer = new Timer();
        btConnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                connectWithBtDevice();
            }
        }, 0, 60000);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        Log.i(Hlpr.__FUNC__(getClass()), "MqttRecieverService started with startId=" + startId + " flags=" + flags + " Intent: " + intent);

        settings = getSharedPreferences(Hlpr.PREFS_NAME, MODE_PRIVATE);
        brokerURI = settings.getString("mqttBrokerUri", "");

        if (brokerURI.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(Hlpr.__FUNC__(getClass()), "isAlreadyConnected()=" + isAlreadyConnected());
        if (!isAlreadyConnected() || !mqttClient.getServerURI().equals(brokerURI)) {
            try {
                Log.i(Hlpr.__FUNC__(getClass()), "mqttClient="+(mqttClient != null ? "set" : "null"));
                Log.i(Hlpr.__FUNC__(getClass()), "brokerURI=" + brokerURI);
                if (mqttClient != null) {
                    Log.i(Hlpr.__FUNC__(getClass()), "getServerURI()=" + mqttClient.getServerURI());
                }
                if (mqttClient != null)
                    mqttClient.disconnect();

                mqttClient = new MqttAndroidClient(getApplicationContext(), brokerURI, getClientId());
                mqttClient.setCallback(this);
                MqttConnectOptions c = new MqttConnectOptions();
                c.setAutomaticReconnect(true);
                mqttClient.connect(c);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }

        if (settings.getBoolean("useMqttTransport", false)) {
            // Tell android not to kill our service (please!).
            return START_STICKY;
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * Service will be shut down. Close all sockets and unregister the broadcast receiver. Then
     * send a broadcast intent to ourselves so that the service gets hopefully restarted.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (btConnectTimer != null)
            btConnectTimer.cancel();

        // Close all bluetooth rfcomm sockets and streams.
        try {
            if (btIn != null)
                btIn.close();
            if (btOut != null)
                btOut.close();
            if (rfcommSocket != null)
                rfcommSocket.close();
            if (isAlreadyConnected())
                mqttClient.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (MqttException e) {
            e.printStackTrace();
        }

        settings = getSharedPreferences(Hlpr.PREFS_NAME, Context.MODE_PRIVATE);
        if (settings.getBoolean("useMqttTransport", false)) {
            // But to be sure our service is restarted after a kill, send a restart broadcast
            sendBroadcast(new Intent("de.evil2000.standheizung.RestartService"));
        }
    }

    @Override
    public void connectComplete(boolean isReconnect, String serverURI) {
        Log.d(Hlpr.__FUNC__(getClass()), "isReconnect=" + isReconnect + " serverURI=" + serverURI);
        if (serverURI.equals(brokerURI)) {
            try {
                IMqttToken res = mqttClient.subscribe(topic_rx, 0);
                res.setActionCallback(new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken token) {
                        Log.d("IMqttActionListener", "Subscription complete.");
                    }

                    @Override
                    public void onFailure(IMqttToken iMqttToken, Throwable cause) {
                        Log.d("IMqttActionListener", "Subscription failed. cause=" + (cause != null ? cause.toString() : "null"));
                        try {
                            mqttClient.subscribe(topic_rx, 0);
                        } catch (MqttException e) {
                            e.printStackTrace();
                        }
                    }
                });
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.d(Hlpr.__FUNC__(getClass()), "cause=" + (cause != null ? cause.toString() : "null"));
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        Log.d(Hlpr.__FUNC__(getClass()), "topic=" + topic + " message=" + message.toString());
        handleMqtt(message);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.d(Hlpr.__FUNC__(getClass()), "token=" + token.toString());
    }

    /*
     * Checks if the MQTT client thinks it has an active connection
     */
    private boolean isAlreadyConnected() {
        return ((mqttClient != null) && mqttClient.isConnected());
    }

    private String getClientId() {
        if (clientId != null)
            return clientId;
        clientId = settings.getString("mqttClientId", "");
        if (!clientId.isEmpty())
            return clientId;
        clientId = MqttClient.generateClientId();
        settings.edit().putString("mqttClientId", clientId).apply();
        Log.d(Hlpr.__FUNC__(getClass()), "mqttClientId=" + clientId);
        return clientId;
    }

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
     * @param message SMS_RECEIVED intent only!
     */
    private void handleMqtt(MqttMessage message) {
        // @formatter:off
        Log.i(Hlpr.__FUNC__(getClass()), "Received MQTT message:\n"
                + "ID       : " + message.getId() + "\n"
                + "QoS      : " + message.getQos() + "\n"
                + "Duplicate: " + message.isDuplicate() + "\n"
                + "Retained : " + message.isRetained() + "\n"
                + "Message  : " + message.toString() + "\n"
        );
        // @formatter:on

        // Split the SMS message by space and treat the second argument as "command"
        String command;
        try {
            command = message.toString().split(" ")[0];
        } catch (ArrayIndexOutOfBoundsException e) {
            command = message.toString();
        }

        String fromApp;
        try {
            fromApp = message.toString().split(" ")[1];
        } catch (ArrayIndexOutOfBoundsException e) {
            fromApp = "";
        }

        // Send the right open/close commands to btDevice if on/off/anlernenX is received.
        if (command.toLowerCase().equals("on")) {
            if (!sendToBt(Hlpr.relayCh1Close))
                return;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                sendMqtt(fromApp.equals("app") ? getString(R.string.ah_on) : "on");
            }
        } else if (command.toLowerCase().equals("off")) {
            if (!sendToBt(Hlpr.relayCh2Close))
                return;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh2Open))
                    return;
                sendMqtt(fromApp.equals("app") ? getString(R.string.ah_off) : "off");
            }
        } else if (command.toLowerCase().equals("anlernen1")) {
            try {
                if (!sendToBt(Hlpr.relayCh2Close))
                    return;

                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(2000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                if (!sendToBt(Hlpr.relayCh2Open))
                    return;
                sendMqtt(getString(R.string.remote_lern1_sent));
            }
        } else if (command.toLowerCase().equals("anlernen2")) {
            try {
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                if (!sendToBt(Hlpr.relayCh2Close))
                    return;
                Thread.sleep(3500);

                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                if (!sendToBt(Hlpr.relayCh2Open))
                    return;
                Thread.sleep(1000);

                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Close))
                    return;
                Thread.sleep(1000);
                if (!sendToBt(Hlpr.relayCh1Open))
                    return;
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Log.w(Hlpr.__FUNC__(getClass()), "Thread.sleep() was interrupted.");
            } finally {
                sendMqtt(getString(R.string.remote_lern2_sent));
            }
        } else {
            sendMqtt(getString(R.string.cmd_not_supported));
        }
    }


    /**
     * Connect to the brDevice which is stored in the shared prefs. Catch the IOException if the
     * connection could not be established.
     *
     * @return True if a connection is established, false otherwise.
     */
    private boolean connectWithBtDevice() {
        if (rfcommSocket != null && rfcommSocket.isConnected())
            return true;

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
        if (btAdapter == null)
            return false;

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
                            btDeviceAddress + ": " + e.getMessage());
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
                sendMqtt(getString(R.string.bt_not_connected));
                return false;
            }
            btOut.write(b);
            btOut.flush();
            return true;
        } catch (IOException e) {
            sendMqtt(getString(R.string.bt_error_sending) + e.getMessage());
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
                sendMqtt(getString(R.string.bt_not_connected));
                return null;
            }
            byte[] b = null;
            btIn.read(b);
            return b;
        } catch (IOException e) {
            sendMqtt(getString(R.string.bt_error_receiving) + e.getMessage());
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
     * Send a MQTT message to the topic.
     *
     * @param text The message to send.
     */
    private void sendMqtt(String text) {
        try {
            mqttClient.publish(topic_tx, text.getBytes(), 0, false);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
