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
import java.util.List;

public class BluetoothBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothBroadcastReceiver";

    private final MainActivity activity;
    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();

    public BluetoothBroadcastReceiver(MainActivity activity) {
        this.activity = activity;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            // ═════════════════════════════════════════════════════════════
            // DEVICE DISCOVERED
            // ═════════════════════════════════════════════════════════════
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (device != null) {
                Log.d(TAG, "✓ Device discovered: " + device.getName() + " @ " + device.getAddress());

                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device);
                }

                // Update UI with discovered devices
                activity.updateBluetoothDeviceList(discoveredDevices);
            }
        }

        if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            // ═════════════════════════════════════════════════════════════
            // DISCOVERY FINISHED
            // ═════════════════════════════════════════════════════════════
            Log.d(TAG, "✓ Bluetooth discovery finished. Found: " + discoveredDevices.size() + " devices");
            activity.onBluetoothDiscoveryFinished(discoveredDevices);
        }

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            // ═════════════════════════════════════════════════════════════
            // BLUETOOTH STATE CHANGED
            // ═════════════════════════════════════════════════════════════
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

        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            // ═════════════════════════════════════════════════════════════
            // PAIRING STATUS CHANGED
            // ═════════════════════════════════════════════════════════════
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
