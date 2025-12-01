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

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.example.dtn.managers.WifiDirectManager;

/**
 * WifiDirectBroadcastReceiver - Updated for MVVM
 * Now delegates to WifiDirectManager instead of MainActivity
 */
public class WifiDirectBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiDirectReceiver";

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final WifiDirectManager wifiDirectManager;

    public WifiDirectBroadcastReceiver(WifiP2pManager manager,
                                       WifiP2pManager.Channel channel,
                                       WifiDirectManager wifiDirectManager) {
        this.manager = manager;
        this.channel = channel;
        this.wifiDirectManager = wifiDirectManager;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "=== Broadcast received: " + action + " ===");

        if (action == null) {
            return;
        }

        // 1. WIFI P2P STATE CHANGED
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);

            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                Log.d(TAG, "✓ Wi-Fi P2P is enabled");
            } else {
                Log.e(TAG, "✗ Wi-Fi P2P is disabled");
            }
        }

        // 2. PEERS CHANGED
        else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            Log.d(TAG, "Peer list changed - requesting peer list");

            if (manager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(context,
                            Manifest.permission.NEARBY_WIFI_DEVICES)
                            == PackageManager.PERMISSION_GRANTED) {
                        manager.requestPeers(channel, peerList -> {
                            wifiDirectManager.onPeersChanged(peerList);
                        });
                    } else {
                        Log.e(TAG, "Missing NEARBY_WIFI_DEVICES permission");
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context,
                            Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        manager.requestPeers(channel, peerList -> {
                            wifiDirectManager.onPeersChanged(peerList);
                        });
                    } else {
                        Log.e(TAG, "Missing LOCATION permission");
                    }
                }
            }
        }

        // 3. CONNECTION CHANGED
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

                    manager.requestConnectionInfo(channel, info -> {
                        wifiDirectManager.onConnectionInfoAvailable(info);
                    });

                } else {
                    Log.d(TAG, "✗ Wi-Fi Direct disconnected");
                    wifiDirectManager.onDisconnected();
                }
            } else {
                Log.w(TAG, "NetworkInfo is null in CONNECTION_CHANGED");
            }
        }

        // 4. THIS DEVICE CHANGED
        else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            Log.d(TAG, "This device info changed");

            WifiP2pDevice device = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class);
            } else {
                device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            }

            if (device != null && device.deviceName != null) {
                Log.d(TAG, "This device: " + device.deviceName);
                Log.d(TAG, "Device status: " + device.status);

                // Notify WifiDirectManager of device info
                // (Manager can update ViewModel if needed)
            }
        }
    }
}