package de.evil2000.standheizung;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

/**
 * This is the Helper class which provides some constants and static functions.
 */
public class Hlpr {
    public static final String PREFS_NAME = "Settings";
    // @formatter:off
    /**
     * Mobile terminal input "A0" (B0 / C0 / D0 is the same), the relay is "open", and after delay of 1 second the relay is "Close".
     */
    public static final byte[] relayCh1Momentary    = "A0\r\n".getBytes();
    /**
     * Mobile terminal input "A1", the relay is "Open", input "A1", the relay is "Close", and so on
     */
    public static final byte[] relayCh1SelfLock     = "A1\r\n".getBytes();
    /**
     * Mobile terminal input "A2", relay 1 is "Open", the other relay is "Close"; input "B2", relay 2 is "Open", the other relay is "Close", and so on.
     */
    public static final byte[] relayCh1Interlock    = "A2\r\n".getBytes();
    /**
     * Relay Open ( NO <-> COM )
     */
    public static final byte[] relayCh1Open         = "A3\r\n".getBytes();
    /**
     * Relay Close ( NC <-> COM )
     */
    public static final byte[] relayCh1Close        = "A4\r\n".getBytes();
    public static final byte[] relayCh2Momentary    = "B0\r\n".getBytes();
    public static final byte[] relayCh2SelfLock     = "B1\r\n".getBytes();
    public static final byte[] relayCh2Interlock    = "B2\r\n".getBytes();
    public static final byte[] relayCh2Open         = "B3\r\n".getBytes();
    public static final byte[] relayCh2Close        = "B4\r\n".getBytes();
    public static final byte[] relayCh3Momentary    = "C1\r\n".getBytes();
    public static final byte[] relayCh3SelfLock     = "C2\r\n".getBytes();
    public static final byte[] relayCh3Interlock    = "C3\r\n".getBytes();
    public static final byte[] relayCh3Open         = "C4\r\n".getBytes();
    public static final byte[] relayCh3Close        = "C0\r\n".getBytes();
    public static final byte[] relayCh4Momentary    = "D0\r\n".getBytes();
    public static final byte[] relayCh4SelfLock     = "D1\r\n".getBytes();
    public static final byte[] relayCh4Interlock    = "D2\r\n".getBytes();
    public static final byte[] relayCh4Open         = "D3\r\n".getBytes();
    public static final byte[] relayCh4Close        = "D4\r\n".getBytes();
    // @formatter:on
    public Hlpr() {
    }

    /**
     * Access the bluetooth adapter or return null if the device does not support bluetooth.
     *
     * @return BluetoothAdapter or null
     */
    public static BluetoothAdapter getBluetoothAdapter() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(__FUNC__(Hlpr.class), "Device does not support Bluetooth. Exiting.");
            return null;
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Log.e(__FUNC__(Hlpr.class), "Bluetooth is not enabled. Exiting.");
            return null;
        }
        return mBluetoothAdapter;
    }

    /**
     * Get the method name for a depth in call stack.
     *
     * @param c The class which includes the currently executed function.
     * @return method name
     */
    public static String __FUNC__(Class<?> c) {
        final StackTraceElement[] ste = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : ste) {
            if (c.getCanonicalName().equals(e.getClassName())) {
                //return e.getMethodName();
                return e.getClassName() + "." + e.getMethodName() + "(" + e.getFileName() + ":" +
                        e.getLineNumber() + ")";
            }
            //Log.d("__FUNC__()", e.getClassName() + " " + e.getMethodName() + " (" + e.getFileName() + ":" + e.getLineNumber() + ")");
        }
        return "";
    }
}