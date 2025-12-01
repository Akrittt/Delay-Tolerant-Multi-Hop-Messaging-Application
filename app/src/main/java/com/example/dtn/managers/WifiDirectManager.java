package com.example.dtn.managers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.network.WifiDirectBroadcastReceiver;
import com.example.dtn.viewmodel.MainViewModel;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * WifiDirectManager - Encapsulates all WiFi Direct operations
 *
 * RESPONSIBILITIES:
 * - Initialize WiFi Direct
 * - Manage peer discovery
 * - Handle connections
 * - Manage server/client threads
 * - Update ViewModel with state
 */
@SuppressLint("MissingPermission")
public class WifiDirectManager {

    private static final String TAG = "WifiDirectManager";

    private final Context context;
    private final MainViewModel viewModel;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiDirectBroadcastReceiver wifiDirectReceiver;
    private IntentFilter intentFilter;

    private ServerThread serverThread;
    private ClientThread clientThread;

    private List<WifiP2pDevice> discoveredPeers = new ArrayList<>();
    private WifiP2pDevice[] deviceArray;

    private boolean isReceiverRegistered = false;
    private boolean isConnected = false;
    private ConnectionManager connectionManager;

    public WifiDirectManager(Context context, MainViewModel viewModel) {
        this.context = context;
        this.viewModel = viewModel;

        initializeManager();
    }

    /**
     * Set ConnectionManager reference
     */
    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        Log.d(TAG, "✓ ConnectionManager injected");
    }

    /**
     * Initialize WiFi Direct manager
     */
    private void initializeManager() {
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);

        if (manager == null) {
            Log.w(TAG, "WiFi Direct not supported");
            return;
        }

        channel = manager.initialize(context, context.getMainLooper(), null);

        if (channel == null) {
            Log.e(TAG, "Failed to initialize WiFi Direct channel");
            return;
        }

        // Setup intent filters
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // Create broadcast receiver
        wifiDirectReceiver = new WifiDirectBroadcastReceiver(manager, channel, this);

        Log.d(TAG, "✓ WiFi Direct manager initialized");
    }

    /**
     * Start WiFi Direct services
     */
    public void start() {
        if (manager == null || channel == null) {
            Log.e(TAG, "Cannot start - manager not initialized");
            return;
        }

        // Register receiver
        if (!isReceiverRegistered && wifiDirectReceiver != null) {
            context.registerReceiver(wifiDirectReceiver, intentFilter);
            isReceiverRegistered = true;
            Log.d(TAG, "✓ Broadcast receiver registered");
        }

        // Start peer discovery
        startPeerDiscovery();

        Log.d(TAG, "✓ WiFi Direct services started");
    }

    /**
     * Stop WiFi Direct services
     */
    public void stop() {
        // Stop discovery
        if (manager != null && channel != null) {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Peer discovery stopped");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Failed to stop discovery: " + reason);
                }
            });
        }

        // Unregister receiver
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiDirectReceiver);
                isReceiverRegistered = false;
                Log.d(TAG, "✓ Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }

        // Close threads
        if (serverThread != null) {
            serverThread.close();
            serverThread = null;
        }

        if (clientThread != null) {
            clientThread.close();
            clientThread = null;
        }

        isConnected = false;

        Log.d(TAG, "✓ WiFi Direct services stopped");
    }

    /**
     * Start peer discovery
     */
    public void startPeerDiscovery() {
        if (manager == null || channel == null) {
            Log.e(TAG, "Cannot discover - manager not initialized");
            return;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing NEARBY_WIFI_DEVICES permission");
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing LOCATION permission");
                return;
            }
        }

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "✓ Peer discovery started");
                viewModel.setConnectionStatus(false, "Discovering...");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Peer discovery failed: " + reason);
                viewModel.setConnectionStatus(false, "Discovery Failed");
            }
        });
    }

    /**
     * Connect to peer device
     */
    public void connectToPeer(WifiP2pDevice device) {
        if (manager == null || channel == null) {
            Log.e(TAG, "Cannot connect - manager not initialized");
            return;
        }

        if (device == null) {
            Log.e(TAG, "Device is null");
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permission required");
                return;
            }
        }

        Log.d(TAG, "Connecting to: " + device.deviceName);

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "✓ Connection initiated to " + device.deviceName);
                viewModel.setConnectionStatus(true, "Connecting...");
            }

            @Override
            public void onFailure(int reason) {
                String failureReason = getConnectionFailureReason(reason);
                Log.e(TAG, "Connection failed: " + failureReason);
                viewModel.setConnectionStatus(false, "Connection Failed");
            }
        });
    }

    /**
     * Handle peer list update (called from BroadcastReceiver)
     */
    public void onPeersChanged(WifiP2pDeviceList peerList) {
        if (peerList == null) {
            Log.w(TAG, "Peer list is null");
            return;
        }

        discoveredPeers.clear();
        discoveredPeers.addAll(peerList.getDeviceList());

        // Create arrays for UI
        deviceArray = new WifiP2pDevice[discoveredPeers.size()];
        List<String> deviceNames = new ArrayList<>();

        int index = 0;
        for (WifiP2pDevice device : discoveredPeers) {
            deviceArray[index] = device;
            deviceNames.add(device.deviceName);
            index++;
        }

        // Update ViewModel
        viewModel.setDiscoveredPeers(deviceNames);

        Log.d(TAG, "✓ Discovered " + discoveredPeers.size() + " WiFi Direct peers");
    }

    /**
     * Handle connection info (called from BroadcastReceiver)
     */
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info == null) {
            Log.w(TAG, "Connection info is null");
            return;
        }

        Log.d(TAG, "=== Connection Info ===");
        Log.d(TAG, "Group Formed: " + info.groupFormed);
        Log.d(TAG, "Is Group Owner: " + info.isGroupOwner);

        if (info.groupOwnerAddress != null) {
            Log.d(TAG, "Group Owner IP: " + info.groupOwnerAddress.getHostAddress());
        }

        if (!info.groupFormed) {
            return;
        }

        isConnected = true;

        // Update device ID from WiFi Direct
        updateDeviceIdFromWifiDirect();

        final InetAddress groupOwnerAddress = info.groupOwnerAddress;

        if (info.isGroupOwner) {
            // This device is group owner (server)
            startServerThread();
            viewModel.setConnectionStatus(true, "Connected as Host");

        } else {
            // This device is client
            startClientThread(groupOwnerAddress);
            viewModel.setConnectionStatus(true, "Connected as Client");
        }
    }

    /**
     * Handle disconnection (called from BroadcastReceiver)
     */
    public void onDisconnected() {
        Log.d(TAG, "WiFi Direct disconnected");

        isConnected = false;

        if (serverThread != null) {
            serverThread.close();
            serverThread = null;
        }

        if (clientThread != null) {
            clientThread.close();
            clientThread = null;
        }

        viewModel.setConnectionStatus(false, "Disconnected");
    }

    /**
     * Start server thread (group owner)
     */
    private void startServerThread() {
        if (serverThread == null || !serverThread.isAlive()) {
            if (connectionManager != null) {
                serverThread = new ServerThread(connectionManager.getMessageHandler());
                serverThread.start();
                Log.d(TAG, "✓ ServerThread started with handler");
            } else {
                Log.e(TAG, "ConnectionManager not set - cannot start server thread");
            }
        }
    }

    /**
     * Start client thread
     */
    private void startClientThread(InetAddress hostAddress) {
        if (clientThread == null || !clientThread.isAlive()) {
            if (connectionManager != null) {
                clientThread = new ClientThread(hostAddress, connectionManager.getMessageHandler());
                clientThread.start();
                Log.d(TAG, "✓ ClientThread started with handler");
            } else {
                Log.e(TAG, "ConnectionManager not set - cannot start client thread");
            }
        }
    }

    /**
     * Update device ID from WiFi Direct device info
     */
    private void updateDeviceIdFromWifiDirect() {
        if (manager != null && channel != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                manager.requestDeviceInfo(channel, device -> {
                    if (device != null && device.deviceName != null) {
                        viewModel.setOwnDeviceId(device.deviceName);
                        Log.d(TAG, "✓ Device ID updated: " + device.deviceName);
                    }
                });
            }
        }
    }

    /**
     * Get device at position (for connection)
     */
    public WifiP2pDevice getDeviceAtPosition(int position) {
        if (deviceArray != null && position >= 0 && position < deviceArray.length) {
            return deviceArray[position];
        }
        return null;
    }

    /**
     * Get connection failure reason string
     */
    private String getConnectionFailureReason(int reason) {
        switch (reason) {
            case WifiP2pManager.ERROR:
                return "Internal error";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P unsupported";
            case WifiP2pManager.BUSY:
                return "Busy";
            default:
                return "Unknown (" + reason + ")";
        }
    }

    /**
     * Check if WiFi Direct is available
     */
    public boolean isAvailable() {
        return manager != null && channel != null;
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Get server thread
     */
    public ServerThread getServerThread() {
        return serverThread;
    }

    /**
     * Get client thread
     */
    public ClientThread getClientThread() {
        return clientThread;
    }

    /**
     * Cleanup
     */
    public void shutdown() {
        stop();
        Log.d(TAG, "✓ WifiDirectManager shutdown");
    }
}