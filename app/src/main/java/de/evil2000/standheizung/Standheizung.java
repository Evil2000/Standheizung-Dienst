package de.evil2000.standheizung;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Set;

public class Standheizung extends Activity {
    public static String publicRfcommUuid = "00001101-0000-1000-8000-00805f9b34fb";
    private SharedPreferences settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_standheizung);
        startService(new Intent(this,SmsReceiverService.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The activity is about to become visible.
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Re-read the shared prefs
        settings = getSharedPreferences(Hlpr.PREFS_NAME, MODE_PRIVATE);

        // Treat the trusted SMS number text input. Store values on leaving focus or done.
        final EditText txtTrustedSmsNumber = (EditText) findViewById(R.id.txtTrustedSmsNumber);
        txtTrustedSmsNumber.setText(settings.getString("smsAuthSender", ""));
        txtTrustedSmsNumber.addTextChangedListener(new PhoneNumberFormattingTextWatcher());
        txtTrustedSmsNumber.setOnFocusChangeListener(new TextView.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) return;
                TextView view = (TextView) v;
                Log.d("onFocusChange", view.getText().toString());
                settings.edit().putString("smsAuthSender", view.getText().toString()).apply();
            }
        });
        txtTrustedSmsNumber.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        event.getAction() == KeyEvent.ACTION_DOWN &&
                                event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    // the user is done typing.
                    Log.d("onEditorAction", view.getText().toString());
                    settings.edit().putString("smsAuthSender", view.getText().toString()).apply();
                    return true; // consume.
                }
                return false;
            }
        });

        // Treat the "Done" button. It closes the activity.
        Button btnDone = (Button) findViewById(R.id.btnDone);
        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Request focus so that the txtTrustedSmsNumber looses focus and onFocusChange gets
                // triggered
                view.requestFocus();
                view.requestFocusFromTouch();
                Standheizung.this.finish();
            }
        });

        // Get the dropdown field
        Spinner drpdwnBluetoothDevice = findViewById(R.id.drpdwnBluetoothDevice);
        // Prepare the array adapter for use with the dropdown field list
        final ArrayAdapter aAdapter =
                new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item);
        // Link the array adapter to the dropdown list
        drpdwnBluetoothDevice.setAdapter(aAdapter);
        // Set the class which reacts on the list item selection
        drpdwnBluetoothDevice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                try {
                    // Store BT address and device name in a custom class, because the array adapter
                    // calls toString() on each element. Therefore the BT address and device name
                    // can stay together.
                    btItem itm = (btItem) aAdapter.getItem(i);
                    Log.d("onItemSelected", "Name: " + itm.name + " Addr: " + itm.addr);
                    // Store the setting.
                    settings.edit().putString("btDeviceAddress", itm.addr).apply();
                } catch (ClassCastException e) {
                    // The ClassCastException will be thrown when the selected item in the array
                    // adapter is not of type <btItem>. Then the exception is catched.
                    Log.d("onItemSelected",
                            "Selected item no " + i + " is not of class <btItem>. Skip selection!");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                Log.d("onNothingSelected", "nothing selected");
            }
        });

        // Access the Bluetooth adapter
        BluetoothAdapter btAtapter = Hlpr.getBluetoothAdapter();
        if (btAtapter == null) {
            aAdapter.add(getString(R.string.no_bt_adapter_fround));
            drpdwnBluetoothDevice.setEnabled(false);
            return;
        }

        // Get the list of already paired devices.
        Set<BluetoothDevice> btDeviceList = btAtapter.getBondedDevices();
        for (BluetoothDevice btDevice : btDeviceList) {
            // Renew the UUIDs by using SDP (maybe not necessary)
            btDevice.fetchUuidsWithSdp();
            // Some BT devices have no registered services
            if (btDevice.getUuids() == null) continue;
            for (ParcelUuid pUuid : btDevice.getUuids()) {
                Log.d(Hlpr.__FUNC__(getClass()),
                        "btDevice: " + btDevice.getName() + " [" + btDevice.getAddress() + "] : " +
                                pUuid.toString());
                // Only list devices which have rfcomm capability
                if (!pUuid.toString().equals(publicRfcommUuid)) continue;
                // Create the btItem and add it to the dropdown list.
                btItem itm = new btItem();
                itm.name = btDevice.getName();
                itm.addr = btDevice.getAddress();
                aAdapter.add(itm);
                // Preselect an already chosen device from the settings
                if (itm.addr.equals(settings.getString("btDeviceAddress", ""))) {
                    drpdwnBluetoothDevice.setSelection(aAdapter.getPosition(itm));
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Another activity is taking focus (this activity is about to be "paused").
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The activity is no longer visible (it is now "stopped")
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The activity is about to be destroyed.
    }

    /**
     * Simple Bluetooth Item class which stores the device address and name for use in the
     * dropdown array adapter.
     */
    private class btItem {
        public String name;
        public String addr;

        public String toString() {
            return name;
        }
    }

}