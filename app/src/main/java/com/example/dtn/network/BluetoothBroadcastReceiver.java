package com.example.dtn.network;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.MainActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BluetoothBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothBroadcastReceiver";

    private final MainActivity activity;
    private List<BluetoothDevice> discoveredDevices = Collections.synchronizedList(new ArrayList<>());

    public BluetoothBroadcastReceiver(MainActivity activity) {
        this.activity = activity;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();


        // 1. DEVICE DISCOVERED
        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (device != null) {
                String deviceName = device.getName() != null ? device.getName() : "Unknown";
                String deviceAddress = device.getAddress();

                Log.d(TAG, "✓ Device discovered: " + deviceName + " @ " + deviceAddress);

                // Add to discovered list
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device);
                }

            }
        }


        // 2. DISCOVERY FINISHED
        if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            Log.d(TAG, "✓ Bluetooth discovery finished. Found: " + discoveredDevices.size() + " devices");
            activity.onBluetoothDiscoveryFinished(discoveredDevices);
            activity.updateBluetoothDeviceList(discoveredDevices);

            activity.autoConnectToFriends(discoveredDevices);


        }

        // 3. BLUETOOTH STATE CHANGED
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

            switch (state) {
                case BluetoothAdapter.STATE_OFF:
                    Log.w(TAG, "Bluetooth turned OFF");
                    activity.onBluetoothStateChanged(false);
                    break;

                case BluetoothAdapter.STATE_ON:
                    Log.d(TAG, "✓ Bluetooth turned ON");
                    activity.onBluetoothStateChanged(true);
                    break;

                case BluetoothAdapter.STATE_TURNING_ON:
                    Log.d(TAG, "Bluetooth turning on...");
                    break;

                case BluetoothAdapter.STATE_TURNING_OFF:
                    Log.d(TAG, "Bluetooth turning off...");
                    break;
            }
        }

        // 4. PAIRING STATUS CHANGED
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

            if (device != null) {
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "✓ Device paired: " + device.getName());
                    activity.onBluetoothDevicePaired(device);
                } else if (bondState == BluetoothDevice.BOND_BONDING) {
                    Log.d(TAG, "Pairing in progress: " + device.getName());
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.w(TAG, "Pairing failed or removed: " + device.getName());
                }
            }
        }
    }
}
