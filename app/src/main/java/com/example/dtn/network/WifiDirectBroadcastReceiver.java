package com.example.dtn.network;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.example.dtn.MainActivity;


public class WifiDirectBroadcastReceiver extends BroadcastReceiver {
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private MainActivity activity;

    public WifiDirectBroadcastReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel, MainActivity activity) {
        this.manager = manager;
        this.channel = channel;
        this.activity = activity;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action == null) {
            return; // Safety check for null action
        }

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Check if Wi-Fi P2P is enabled
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                Toast.makeText(context, "Wi-Fi P2P is ON", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Wi-Fi P2P is OFF", Toast.LENGTH_SHORT).show();
            }

        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // Request available peer list
            if (manager == null || channel == null) {
                Log.e("WifiDirectBroadcast", "Manager or channel is null");
                return;
            }

            // Check permissions based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+) - Check NEARBY_WIFI_DEVICES
                if (ActivityCompat.checkSelfPermission(activity,
                        Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("WifiDirectBroadcast", "Missing NEARBY_WIFI_DEVICES permission");
                    Toast.makeText(context, "Missing Wi-Fi permission", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                // Android 12 and below - Check ACCESS_FINE_LOCATION
                if (ActivityCompat.checkSelfPermission(activity,
                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("WifiDirectBroadcast", "Missing ACCESS_FINE_LOCATION permission");
                    Toast.makeText(context, "Missing location permission", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            manager.requestPeers(channel, activity.peerListListener);

        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // Handle connection state changes
            if (manager == null || channel == null) {
                Log.e("WifiDirectBroadcast", "Manager or channel is null");
                return;
            }

            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo != null && networkInfo.isConnected()) {
                // We are connected - request connection details
                Log.d("WifiDirectBroadcast", "Connected to peer");

                // Check permissions before requesting connection info
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(activity,
                            Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                        Log.e("WifiDirectBroadcast", "Missing NEARBY_WIFI_DEVICES permission for connection info");
                        return;
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(activity,
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        Log.e("WifiDirectBroadcast", "Missing ACCESS_FINE_LOCATION permission for connection info");
                        return;
                    }
                }

                manager.requestConnectionInfo(channel, activity.connectionInfoListener);
            } else {
                // Disconnected or intermediate state
                Log.d("WifiDirectBroadcast", "Disconnected from peer");
                activity.onDisconnect();
                activity.statusTextView.setText("Status: Disconnected");
            }

        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // Update device information
            WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (device != null) {
                activity.setOwnDeviceName(device.deviceName);
                Log.d("WifiDirectBroadcast", "Device name: " + device.deviceName);
            } else {
                Log.w("WifiDirectBroadcast", "Device info is null");
            }
        }
    }
}
