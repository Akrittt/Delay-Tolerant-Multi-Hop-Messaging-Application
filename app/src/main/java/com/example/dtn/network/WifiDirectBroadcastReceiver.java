package com.example.dtn.network;

import static android.content.ContentValues.TAG;

import static com.example.dtn.MainActivity.PREFS_NAME;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
        Log.d(TAG, "=== Broadcast received: " + action + " ===");

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                Log.d(TAG, "✓ Wi-Fi P2P is enabled");
            } else {
                Log.e(TAG, "✗ Wi-Fi P2P is disabled");
                activity.statusTextView.setText("Status: Wi-Fi Direct Disabled");
            }
        }
        else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            Log.d(TAG, "Peer list changed - requesting peer list");

            if (manager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(context,
                            Manifest.permission.NEARBY_WIFI_DEVICES)
                            == PackageManager.PERMISSION_GRANTED) {
                        manager.requestPeers(channel, activity.peerListListener);
                    } else {
                        Log.e(TAG, "Missing NEARBY_WIFI_DEVICES permission");
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context,
                            Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        manager.requestPeers(channel, activity.peerListListener);
                    } else {
                        Log.e(TAG, "Missing LOCATION permission");
                    }
                }
            }
        }
        else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            Log.d(TAG, "=== CONNECTION_CHANGED_ACTION received ===");

            if (manager == null) {
                Log.e(TAG, "Manager is null in CONNECTION_CHANGED");
                return;
            }

            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            if (networkInfo != null) {
                Log.d(TAG, "NetworkInfo state: " + networkInfo.getState());
                Log.d(TAG, "NetworkInfo isConnected: " + networkInfo.isConnected());
                Log.d(TAG, "NetworkInfo detailed state: " + networkInfo.getDetailedState());

                if (networkInfo.isConnected()) {
                    Log.d(TAG, "✓ Wi-Fi Direct connection established! Requesting connection info...");

                    // Check permissions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(context,
                                Manifest.permission.NEARBY_WIFI_DEVICES)
                                != PackageManager.PERMISSION_GRANTED) {
                            Log.e(TAG, "Missing NEARBY_WIFI_DEVICES permission");
                            return;
                        }
                    } else {
                        if (ActivityCompat.checkSelfPermission(context,
                                Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED) {
                            Log.e(TAG, "Missing LOCATION permission");
                            return;
                        }
                    }

                    manager.requestConnectionInfo(channel, activity.connectionInfoListener);

                } else {
                    Log.d(TAG, "✗ Wi-Fi Direct disconnected");
                    activity.onDisconnect();
                }
            } else {
                Log.w(TAG, "NetworkInfo is null in CONNECTION_CHANGED");
            }
        }
        else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            Log.d(TAG, "This device info changed");

            WifiP2pDevice device = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class);
            } else {
                device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            }

            if (device != null && device.deviceName != null ) {
                activity.ownDeviceId = device.deviceName;
                Log.d(TAG, "This device MAC: " + device.deviceName);

                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, 0);
                prefs.edit().putString("ACTUAL_DEVICE_MAC", activity.ownDeviceId).apply();

                Log.d(TAG, "Device address: " + device.deviceName);
                Log.d(TAG, "Device status: " + device.status);
//
            }
        }
    }

}
