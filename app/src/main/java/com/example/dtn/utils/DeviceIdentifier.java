package com.example.dtn.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.net.wifi.p2p.WifiP2pDevice;
import android.util.Log;

import androidx.annotation.RequiresPermission;

/**
 * Utility class for consistent device identification across the app
 */
public class DeviceIdentifier {
    private static final String TAG = "DeviceIdentifier";

    /**
     * Get consistent identifier for Bluetooth device (uses MAC address)
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public static String getBluetoothDeviceId(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }

        //  use MAC address for consistency
        String address = device.getAddress();
        if (address != null && !address.isEmpty()) {
            return address;
        }

        // Fallback to name if address unavailable
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                Log.w(TAG, "Using device name as fallback ID: " + name);
                return name;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot get device name", e);
        }

        return "unknown_bt_device";
    }

    /**
     * Get consistent identifier for WiFi Direct device (uses device address)
     */
    public static String getWifiDirectDeviceId(WifiP2pDevice device) {
        if (device == null) {
            return "unknown";
        }

        // FIXED: Always use device address for consistency
        if (device.deviceAddress != null && !device.deviceAddress.isEmpty()) {
            return device.deviceAddress;
        }

        // Fallback to device name
        if (device.deviceName != null && !device.deviceName.isEmpty()) {
            Log.w(TAG, "Using device name as fallback ID: " + device.deviceName);
            return device.deviceName;
        }

        return "unknown_wifi_device";
    }

    /**
     * Get display name for Bluetooth device
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public static String getBluetoothDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "Unknown Device";
        }

        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot get device name", e);
        }

        // Fallback to address
        return "Device_" + device.getAddress().replace(":", "");
    }

    /**
     * Get display name for WiFi Direct device
     */
    public static String getWifiDirectDeviceName(WifiP2pDevice device) {
        if (device == null) {
            return "Unknown Device";
        }

        if (device.deviceName != null && !device.deviceName.isEmpty()) {
            return device.deviceName;
        }

        if (device.deviceAddress != null && !device.deviceAddress.isEmpty()) {
            return "Device_" + device.deviceAddress.replace(":", "");
        }

        return "Unknown Device";
    }

    /**
     * Check if two device IDs match (handles both address and name comparisons)
     */
    public static boolean deviceIdsMatch(String id1, String id2) {
        if (id1 == null || id2 == null) {
            return false;
        }
        return id1.equals(id2);
    }
}