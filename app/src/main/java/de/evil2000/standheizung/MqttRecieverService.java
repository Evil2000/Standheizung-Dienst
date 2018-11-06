package de.evil2000.standheizung;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
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
    private String brokerURI = "tcp://broker.hivemq.com:1883";
    private final String topic_rx = "/AH/to";
    private final String topic_tx = "/AH/from";

    @Override
    public void onCreate(){
        super.onCreate();

        // Run a thread which tries every 10 seconds to connect to btDevice forever. Keep in mind
        // that a connection timeout may occur after 5 to 7 seconds which is independent from timer.
        /*Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                connectWithBtDevice();
            }
        },0,10000);*/

        mqttClient = new MqttAndroidClient(getApplicationContext(), brokerURI, getClientId());
        mqttClient.setCallback(this);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        Log.i(Hlpr.__FUNC__(getClass()),"SmsReceiverService started with startId=" + startId + " flags=" + flags +" Intent: " + intent);
            if (!isAlreadyConnected()) {
                try {
                    mqttClient.connect();
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }

        return START_STICKY;
    }

    /**
     * Service will be shut down. Close all sockets and unregister the broadcast receiver. Then
     * send a broadcast intent to ourselves so that the service gets hopefully restarted.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        // Close all bluetooth rfcomm sockets and streams.
        try {
            if (btIn != null)
                btIn.close();
            if(btOut != null)
                btOut.close();
            if(rfcommSocket != null)
                rfcommSocket.close();
            if (isAlreadyConnected())
                mqttClient.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (MqttException e) {
            e.printStackTrace();
        }

        // But to be sure our service is restarted after a kill, send a restart broadcast
        sendBroadcast(new Intent("de.evil2000.standheizung.RestartService"));
    }

    @Override
    public void connectComplete(boolean isReconnect, String serverURI) {
        Log.d(Hlpr.__FUNC__(getClass()),"isReconnect="+isReconnect+" serverURI="+serverURI);
        if (serverURI.equals(brokerURI)) {
            try {
                IMqttToken res = mqttClient.subscribe(topic_rx, 0);
                res.setActionCallback(new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken token) {
                        Log.d("IMqttActionListener","Subscription complete.");
                    }

                    @Override
                    public void onFailure(IMqttToken iMqttToken, Throwable cause) {
                        Log.d("IMqttActionListener","Subscription failed. cause="+cause.toString());
                    }
                });
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.d(Hlpr.__FUNC__(getClass()),"cause="+cause.toString());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {Log.d("","");
        Log.d(Hlpr.__FUNC__(getClass()),"topic="+topic+" message="+message.getPayload().toString());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.d(Hlpr.__FUNC__(getClass()),"token="+token.toString());
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
        SharedPreferences shrdPrefs = getSharedPreferences("MQTTprefs", MODE_PRIVATE);
        clientId = shrdPrefs.getString("ClientId",null);
        if (clientId != null)
            return clientId;
        SharedPreferences.Editor e = shrdPrefs.edit();
        clientId = MqttClient.generateClientId();
        e.putString("ClientId",clientId);
        e.apply();
        return clientId;
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Method not implemented");
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
                // TODO:
                // Send via MQTT
                //sendSms(getString(R.string.bt_not_connected));
                return false;
            }
            btOut.write(b);
            btOut.flush();
            return true;
        } catch (IOException e) {
            // TODO:
            // Send via MQTT
            //sendSms(getString(R.string.bt_error_sending) + e.getMessage());
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
                // TODO:
                // Send via MQTT
                //sendSms(getString(R.string.bt_not_connected));
                return null;
            }
            byte[] b = null;
            btIn.read(b);
            return b;
        } catch (IOException e) {
            // TODO:
            // Send via MQTT
            //sendSms(getString(R.string.bt_error_receiving) + e.getMessage());
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
}
