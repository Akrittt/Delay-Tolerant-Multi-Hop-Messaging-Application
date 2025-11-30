package com.example.dtn.network;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.managers.BluetoothManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BluetoothBroadcastReceiver - Updated for MVVM
 * Now delegates to BluetoothManager instead of MainActivity
 */
public class BluetoothBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BTBroadcastReceiver";

    private final BluetoothManager bluetoothManager;
    private final List<BluetoothDevice> discoveredDevices =
            Collections.synchronizedList(new ArrayList<>());
    private final Set<String> discoveredAddresses =
            Collections.synchronizedSet(new HashSet<>());

    public BluetoothBroadcastReceiver(BluetoothManager bluetoothManager) {
        this.bluetoothManager = bluetoothManager;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action == null) {
            return;
        }

        Log.d(TAG, "=== Broadcast Action: " + action + " ===");

        // 1. DEVICE DISCOVERED
        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (device == null) {
                Log.w(TAG, "Received ACTION_FOUND with null device");
                return;
            }

            String deviceAddress = device.getAddress();

            // Check if already discovered
            synchronized (discoveredAddresses) {
                if (discoveredAddresses.contains(deviceAddress)) {
                    return;
                }
            }

            // Get local address to skip self
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            String localAddress = null;

            if (adapter != null) {
                try {
                    localAddress = adapter.getAddress();
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot get local address", e);
                }
            }

            // Skip self device
            if (localAddress != null && device.getAddress().equals(localAddress)) {
                Log.d(TAG, "✗ Skipped self-device: " + localAddress);
                return;
            }

            // Check by name too
            try {
                String localName = adapter != null ? adapter.getName() : null;
                String deviceName = device.getName();

                if (localName != null && deviceName != null && localName.equals(deviceName)) {
                    Log.d(TAG, "✗ Skipped self-device by name: " + localName);
                    return;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot compare device names", e);
            }

            // Check if DTN-compatible
            if (!isDTNDevice(device)) {
                Log.d(TAG, "✗ Skipped non-DTN device: " + device.getName());
                return;
            }

            // Add to list
            synchronized (discoveredDevices) {
                discoveredDevices.add(device);
                discoveredAddresses.add(deviceAddress);
            }

            String devName = "Unknown";
            try {
                devName = device.getName();
                if (devName == null) devName = "Unknown";
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot get device name", e);
            }
            Log.d(TAG, "✓ Added device: " + devName + " (" + deviceAddress + ")");

            // Notify BluetoothManager
            bluetoothManager.onDeviceDiscovered(device);
        }

        // 2. DISCOVERY FINISHED
        else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            Log.d(TAG, "=== Discovery Finished ===");

            int deviceCount;
            synchronized (discoveredDevices) {
                deviceCount = discoveredDevices.size();
            }

            Log.d(TAG, "Total devices found: " + deviceCount);

            // Notify BluetoothManager
            List<BluetoothDevice> devicesCopy = getDiscoveredDevicesCopy();
            bluetoothManager.onDiscoveryFinished(devicesCopy);
        }

        // 3. BLUETOOTH STATE CHANGED
        else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

            switch (state) {
                case BluetoothAdapter.STATE_OFF:
                    Log.w(TAG, "Bluetooth turned OFF");
                    resetDiscovery();
                    bluetoothManager.onBluetoothStateChanged(false);
                    break;

                case BluetoothAdapter.STATE_ON:
                    Log.d(TAG, "✓ Bluetooth turned ON");
                    bluetoothManager.onBluetoothStateChanged(true);
                    break;

                case BluetoothAdapter.STATE_TURNING_ON:
                    Log.d(TAG, "Bluetooth turning on...");
                    break;

                case BluetoothAdapter.STATE_TURNING_OFF:
                    Log.d(TAG, "Bluetooth turning off...");
                    resetDiscovery();
                    break;
            }
        }

        // 4. PAIRING STATUS CHANGED
        else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

            if (device != null) {
                String devName = "Unknown";
                try {
                    devName = device.getName();
                    if (devName == null) devName = "Unknown";
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot get device name", e);
                }

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "✓ Device paired: " + devName);
                    bluetoothManager.onDevicePaired(device);
                } else if (bondState == BluetoothDevice.BOND_BONDING) {
                    Log.d(TAG, "Pairing in progress: " + devName);
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.w(TAG, "Pairing failed or removed: " + devName);
                }
            }
        }
    }

    /**
     * Check if device is DTN-compatible
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private boolean isDTNDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }

        try {
            int deviceType = device.getType();

            // Accept Classic and Dual mode devices
            if (deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                    deviceType == BluetoothDevice.DEVICE_TYPE_DUAL) {

                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot get device name for DTN check");
                }

                if (name != null && !name.isEmpty() && name.length() < 50) {
                    int deviceClass = device.getBluetoothClass().getDeviceClass();
                    boolean isAudioDevice = (deviceClass & 0x400) != 0;

                    if (!isAudioDevice) {
                        return true;
                    }
                }
            }

            // Accept BLE devices with names
            if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    // Ignore
                }

                if (name != null && !name.isEmpty()) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking device: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reset discovery for fresh scan
     */
    public void resetDiscovery() {
        Log.d(TAG, "Resetting discovery lists");
        synchronized (discoveredDevices) {
            discoveredDevices.clear();
            discoveredAddresses.clear();
        }
    }

    /**
     * Get thread-safe copy of discovered devices
     */
    public List<BluetoothDevice> getDiscoveredDevicesCopy() {
        synchronized (discoveredDevices) {
            return new ArrayList<>(discoveredDevices);
        }
    }
}