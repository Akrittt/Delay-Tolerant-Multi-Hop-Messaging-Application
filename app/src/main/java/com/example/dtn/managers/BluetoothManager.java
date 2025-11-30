package com.example.dtn.managers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import com.example.dtn.model.data.Friend;
import com.example.dtn.network.BluetoothBroadcastReceiver;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.utils.DeviceIdentifier;
import com.example.dtn.viewmodel.MainViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BluetoothManager - Complete implementation
 * Encapsulates all Bluetooth operations
 */
@SuppressLint("MissingPermission")
public class BluetoothManager {

    private static final String TAG = "BluetoothManager";
    private static final String PREFS_NAME = "DTNPrefs";
    private static final int MAX_CLIENT_CONNECTIONS = 7;

    private final Context context;
    private final MainViewModel viewModel;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothBroadcastReceiver bluetoothReceiver;
    private IntentFilter bluetoothIntentFilter;
    private BluetoothServerThread bluetoothServerThread;

    private List<BluetoothClientThread> clientThreads = new CopyOnWriteArrayList<>();
    private Set<String> connectedDeviceAddresses = new HashSet<>();
    private List<BluetoothDevice> discoveredDevices = new ArrayList<>();

    private boolean isReceiverRegistered = false;
    private Handler mainHandler;
    private ConnectionManager connectionManager;

    public BluetoothManager(Context context, MainViewModel viewModel) {
        this.context = context;
        this.viewModel = viewModel;
        this.mainHandler = new Handler(Looper.getMainLooper());

        initializeAdapter();
    }

    /**
     * Set ConnectionManager reference
     */
    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        Log.d(TAG, "✓ ConnectionManager injected");
    }

    /**
     * Initialize Bluetooth adapter
     */
    private void initializeAdapter() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth not available");
            return;
        }

        // Setup intent filters
        bluetoothIntentFilter = new IntentFilter();
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        // Create broadcast receiver
        bluetoothReceiver = new BluetoothBroadcastReceiver(this);

        Log.d(TAG, "✓ Bluetooth adapter initialized");
    }

    /**
     * Start Bluetooth services
     */
    public void start() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot start - adapter not available");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not enabled");
            return;
        }

        // Register receiver
        if (!isReceiverRegistered && bluetoothReceiver != null) {
            context.registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
            isReceiverRegistered = true;
            Log.d(TAG, "✓ Broadcast receiver registered");
        }

        // Start server thread
        startServerThread();

        // Start discovery
        startDiscovery();

        Log.d(TAG, "✓ Bluetooth services started");
    }

    /**
     * Stop Bluetooth services
     */
    public void stop() {
        // Stop discovery
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Unregister receiver
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver);
                isReceiverRegistered = false;
                Log.d(TAG, "✓ Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }

        // Close server thread
        if (bluetoothServerThread != null) {
            bluetoothServerThread.close();
            bluetoothServerThread = null;
        }

        // Close all client threads
        for (BluetoothClientThread client : clientThreads) {
            client.close();
        }
        clientThreads.clear();
        connectedDeviceAddresses.clear();

        Log.d(TAG, "✓ Bluetooth services stopped");
    }

    /**
     * Start Bluetooth server thread
     */
    private void startServerThread() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Cannot start server - adapter not available");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PermissionChecker.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                return;
            }
        }

        if (bluetoothServerThread == null || !bluetoothServerThread.isAlive()) {
            try {
                if (connectionManager != null) {
                    bluetoothServerThread = new BluetoothServerThread(
                            bluetoothAdapter,
                            connectionManager.getMessageHandler()
                    );
                    bluetoothServerThread.start();
                    Log.d(TAG, "✓ Bluetooth server thread started with handler");
                } else {
                    Log.e(TAG, "ConnectionManager not set - cannot start server thread");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting server thread", e);
            }
        }
    }

    /**
     * Start device discovery
     */
    public void startDiscovery() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot discover - adapter not available");
            return;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PermissionChecker.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_SCAN permission not granted");
                return;
            }
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Clear previous discoveries
        discoveredDevices.clear();

        boolean started = bluetoothAdapter.startDiscovery();

        if (started) {
            Log.d(TAG, "✓ Discovery started");
            viewModel.setConnectionStatus(false, "Discovering...");
        } else {
            Log.e(TAG, "Failed to start discovery");
        }
    }

    /**
     * Connect to Bluetooth device
     */
    public void connectToDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Device is null");
            return;
        }

        String deviceAddress = device.getAddress();
        String deviceName = getDeviceName(device);

        // Check if already connected
        if (connectedDeviceAddresses.contains(deviceAddress)) {
            Log.d(TAG, "Already connected to: " + deviceName);
            return;
        }

        // Check connection limit
        if (clientThreads.size() >= MAX_CLIENT_CONNECTIONS) {
            Log.w(TAG, "Max client connections reached");
            mainHandler.post(() ->
                    Toast.makeText(context, "Max connections reached", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        Log.d(TAG, "Connecting to: " + deviceName);

        if (connectionManager != null) {
            BluetoothClientThread clientThread = new BluetoothClientThread(
                    device,
                    connectionManager.getMessageHandler(),
                    context
            );
            clientThreads.add(clientThread);
            clientThread.start();

            connectedDeviceAddresses.add(deviceAddress);

            mainHandler.post(() ->
                    Toast.makeText(context, "Connecting to " + deviceName + "...",
                            Toast.LENGTH_SHORT).show()
            );
        } else {
            Log.e(TAG, "ConnectionManager not set - cannot connect");
        }
    }


    // ==================== Callback Methods (from BroadcastReceiver) ====================

    /**
     * Called when a device is discovered
     */
    public void onDeviceDiscovered(BluetoothDevice device) {
        if (device == null) {
            return;
        }

        // Add to discovered list if not already there
        boolean alreadyExists = false;
        for (BluetoothDevice existing : discoveredDevices) {
            if (existing.getAddress().equals(device.getAddress())) {
                alreadyExists = true;
                break;
            }
        }

        if (!alreadyExists) {
            discoveredDevices.add(device);
            updatePeerList();
        }
    }

    /**
     * Called when discovery is finished
     */
    public void onDiscoveryFinished(List<BluetoothDevice> devices) {
        if (devices == null || devices.isEmpty()) {
            Log.d(TAG, "No devices found");
            viewModel.setConnectionStatus(false, "No devices found");
            return;
        }

        Log.d(TAG, "Discovery finished: " + devices.size() + " devices");

        discoveredDevices.clear();
        discoveredDevices.addAll(devices);

        updatePeerList();

        // Auto-connect to friends
        autoConnectToFriends();

        viewModel.setConnectionStatus(false, "Found " + devices.size() + " device(s)");
    }

    /**
     * Called when Bluetooth state changes
     */
    public void onBluetoothStateChanged(boolean enabled) {
        if (enabled) {
            Log.d(TAG, "Bluetooth enabled");
            viewModel.setConnectionStatus(true, "Bluetooth ON");
            startDiscovery();
        } else {
            Log.d(TAG, "Bluetooth disabled");
            viewModel.setConnectionStatus(false, "Bluetooth OFF");
            discoveredDevices.clear();
            updatePeerList();
        }
    }

    /**
     * Called when a device is paired
     */
    public void onDevicePaired(BluetoothDevice device) {
        Log.d(TAG, "Device paired: " + getDeviceName(device));
        // Optionally auto-connect to paired device
        connectToDevice(device);
    }

    // ==================== Helper Methods ====================

    /**
     * Update peer list in ViewModel
     */
    private void updatePeerList() {
        List<String> deviceNames = new ArrayList<>();

        for (BluetoothDevice device : discoveredDevices) {
            String name = getDeviceName(device);
            String address = device.getAddress();

            // Show connection status
            if (connectedDeviceAddresses.contains(address)) {
                deviceNames.add(name + " ✓ (Connected)");
            } else {
                deviceNames.add(name);
            }
        }

        viewModel.setDiscoveredPeers(deviceNames);
        Log.d(TAG, "✓ Updated peer list: " + deviceNames.size() + " peers");
    }

    /**
     * Auto-connect to friends
     */
    private void autoConnectToFriends() {
        // Observe friends from ViewModel
        viewModel.getAllFriends().observeForever(friends -> {
            if (friends == null || friends.isEmpty()) {
                return;
            }

            // Get friend device IDs
            Set<String> friendIds = new HashSet<>();
            for (Friend friend : friends) {
                friendIds.add(friend.deviceId);
            }

            // Connect to discovered friends
            for (BluetoothDevice device : discoveredDevices) {
                String deviceName = getDeviceName(device);
                String deviceAddress = device.getAddress();

                if (friendIds.contains(deviceName) || friendIds.contains(deviceAddress)) {
                    if (!connectedDeviceAddresses.contains(deviceAddress)) {
                        Log.d(TAG, "Auto-connecting to friend: " + deviceName);
                        connectToDevice(device);

                        // Small delay between connections
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
    }

    /**
     * Update connection status in ViewModel
     */
    public void updateConnectionStatus() {
        int activeConnections = clientThreads.size();

        if (bluetoothServerThread != null) {
            activeConnections += bluetoothServerThread.getConnectedClientCount();
        }

        viewModel.setMeshConnectionStatus(activeConnections);
        Log.d(TAG, "Connection status updated: " + activeConnections + " active connections");
    }

    /**
     * Get device name safely
     */
    private String getDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "Unknown";
        }
        return DeviceIdentifier.getBluetoothDeviceId(device);
    }

    /**
     * Get device at position (for connection)
     */
    public BluetoothDevice getDeviceAtPosition(int position) {
        if (position >= 0 && position < discoveredDevices.size()) {
            return discoveredDevices.get(position);
        }
        return null;
    }

    /**
     * Get device name for device ID
     */
    public String initializeDeviceName() {
        if (bluetoothAdapter == null) {
            return "02:00:00:00:00:00";
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedId = prefs.getString("SAVED_DEVICE_ID", null);

        if (savedId != null && !savedId.isEmpty() && !savedId.equals("02:00:00:00:00:00")) {
            Log.d(TAG, "✓ Loaded saved Device ID: " + savedId);
            return savedId;
        }

        if (bluetoothAdapter.isEnabled()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PermissionChecker.PERMISSION_GRANTED) {
                        String btName = bluetoothAdapter.getName();
                        if (btName != null && !btName.isEmpty()) {
                            prefs.edit().putString("SAVED_DEVICE_ID", btName).apply();
                            Log.d(TAG, "✓ Device ID from BT Name: " + btName);
                            return btName;
                        }
                    }
                } else {
                    String btName = bluetoothAdapter.getName();
                    if (btName != null && !btName.isEmpty()) {
                        prefs.edit().putString("SAVED_DEVICE_ID", btName).apply();
                        Log.d(TAG, "✓ Device ID from BT Name: " + btName);
                        return btName;
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error getting BT name", e);
            }
        }

        try {
            String deviceName = Settings.Global.getString(context.getContentResolver(), "device_name");
            if (deviceName != null && !deviceName.isEmpty()) {
                prefs.edit().putString("SAVED_DEVICE_ID", deviceName).apply();
                Log.d(TAG, "✓ Device ID from Settings: " + deviceName);
                return deviceName;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting device name", e);
        }

        Log.w(TAG, "❌ Failed to initialize device name");
        return "02:00:00:00:00:00";
    }

    /**
     * Check if Bluetooth is available and enabled
     */
    public boolean isAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * Get server thread
     */
    public BluetoothServerThread getServerThread() {
        return bluetoothServerThread;
    }

    /**
     * Get client threads
     */
    public List<BluetoothClientThread> getClientThreads() {
        return new ArrayList<>(clientThreads);
    }

    /**
     * Cleanup
     */
    public void shutdown() {
        stop();
        Log.d(TAG, "✓ BluetoothManager shutdown");
    }
}