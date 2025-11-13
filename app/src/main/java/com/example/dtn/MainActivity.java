package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.dtn.data.AppDatabase;
import com.example.dtn.data.Friend;
import com.example.dtn.data.FriendDao;
import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
import com.example.dtn.network.BluetoothBroadcastReceiver;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.network.WifiDirectBroadcastReceiver;
import com.example.dtn.routing.EpidemicRouting;
import com.example.dtn.routing.RoutingProtocol;
import com.example.dtn.routing.SprayAndWaitRouting;
import com.example.dtn.security.CryptoUtils;
import com.example.dtn.utils.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint({"SetTextI18n", "MissingPermission", "NewApi"})
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // --- Database & Data ---
    AppDatabase database;
    MessageDao messageDao;
    FriendDao friendDao;

    // --- UI Elements ---
    public TextView statusTextView;
    public TextView protocolTextView;
    public TextView transportTextView;
    ListView peerListView, chatListView;
    EditText messageEditText;
    Button sendButton;
    Spinner prioritySpinner;
    Toolbar toolbar;

    // --- Wi-Fi Direct ---
    WifiP2pManager manager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver wifiDirectBroadcastReceiver; // FIXED: renamed from wifiDirectReceiver
    IntentFilter wifiDirectIntentFilter;
    List<WifiP2pDevice> peers = new ArrayList<>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;
    private boolean isWifiDirectReceiverRegistered = false;
    private final CopyOnWriteArrayList <BluetoothDevice> discoveredDevices = new CopyOnWriteArrayList<>();

    // --- Transport Selection ---
    private enum TransportType {WIFI_DIRECT, BLUETOOTH}
    private TransportType activeTransport = TransportType.WIFI_DIRECT;
    private static final String TRANSPORT_PREF = "ActiveTransport";

    // --- Networking & Threads ---
    ServerThread serverThread;
    ClientThread clientThread;
    Handler handler;

    ExecutorService executor = Executors.newSingleThreadExecutor();

    // --- Chat Data ---
    ArrayList<ChatMessage> chatMessages = new ArrayList<>();
    ArrayAdapter<ChatMessage> chatAdapter;

    // --- Logging & Routing ---
    Logger logger;
    RoutingProtocol activeRoutingProtocol;
    EpidemicRouting epidemicRouting;
    SprayAndWaitRouting sprayAndWaitRouting;
    public static final String PREFS_NAME = "DTNPrefs";
    public static final String KEY_ROUTING_PROTOCOL = "RoutingProtocol";
    private String currentProtocol = "EPIDEMIC";
    public String ownDeviceId = null;
    private int connectionAttemptId = 0;

    private final Queue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean isDeviceIdReady = false;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothBroadcastReceiver bluetoothReceiver;
    private IntentFilter bluetoothIntentFilter;
    private BluetoothServerThread bluetoothServerThread;
    private BluetoothClientThread bluetoothClientThread;
    public static volatile boolean isBluetoothConnected = false;
    private String connectedBluetoothDeviceName = null;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    public boolean isBluetoothReceiverRegistered = false;
    private Handler discoveryHandler = new Handler(Looper.getMainLooper());
    private Handler discoveryRetryHandler = new Handler(Looper.getMainLooper());
    private Runnable discoveryRunnable;
    private boolean enableAutoConnect = true;
    private Set<String> connectedDeviceAddresses = new HashSet<>();

    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_CONNECTION_ESTABLISHED = 2;
    public static final int MESSAGE_CONNECTION_LOST = 3;
    private long lastConnectionLostTime = 0;

    private List<BluetoothClientThread> bluetoothClientThreads = new CopyOnWriteArrayList<>();
    private static final int MAX_CLIENT_CONNECTIONS = 7; // Bluetooth Classic limit
    private static final int MIN_MESH_CONNECTIONS = 2; // Minimum for mesh
    private Map<String, Long> connectionAttempts = new ConcurrentHashMap<>();
    private static final long CONNECTION_RETRY_DELAY = 30000; // 30 seconds
    private boolean isInitialized = false;
    private boolean isDiscoverableDialogShown = false;
    private boolean isPermissionDialogShown = false;
    private ScanCallback bleScanCallback = null;


    public static class ChatMessage {
        String text;
        String messageId;
        boolean isDelivered;

        ChatMessage(String text, String messageId, boolean isDelivered) {
            this.text = text;
            this.messageId = messageId;
            this.isDelivered = isDelivered;
        }

        @NonNull
        @Override
        public String toString() {
            return text + (isDelivered ? " ✓" : " ...");
        }
    }

    // Message queue for DTN - wait until device ID is set
    private static class PendingMessage {
        String destination;
        String text;
        long timestamp;

        PendingMessage(String destination, String text) {
            this.destination = destination;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @SuppressLint("HardwareIds")
    private void initializeMyDeviceName() {
        // Try to load saved device ID
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedId = prefs.getString("SAVED_DEVICE_ID", null);

        if (savedId != null && !savedId.isEmpty() && !savedId.equals("02:00:00:00:00:00")) {
            ownDeviceId = savedId;
            Log.d(TAG, "✓ Loaded saved Device ID: " + ownDeviceId);
            return;
        }

        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            try {
                // Check permission for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        String btName = bluetoothAdapter.getName();
                        if (btName != null && !btName.isEmpty()) {
                            ownDeviceId = btName;
                            Log.d(TAG, "✓ Device ID from BT Name: " + ownDeviceId);
                            prefs.edit().putString("SAVED_DEVICE_ID", ownDeviceId).apply();
                            return;
                        }
                    } else {
                        Log.w(TAG, "BLUETOOTH_CONNECT permission not granted");
                    }
                } else {
                    // Android 11 and below
                    String btName = bluetoothAdapter.getName();
                    if (btName != null && !btName.isEmpty()) {
                        ownDeviceId = btName;
                        Log.d(TAG, "✓ Device ID from BT Name: " + ownDeviceId);
                        prefs.edit().putString("SAVED_DEVICE_ID", ownDeviceId).apply();
                        return;
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error getting BT name", e);
            }
        }


        // Try Android device name from Settings
        try {
            String deviceName = Settings.Global.getString(getContentResolver(), "device_name");
            if (deviceName != null && !deviceName.isEmpty()) {
                ownDeviceId = deviceName;
                Log.d(TAG, "✓ Device ID from Settings: " + ownDeviceId);
                prefs.edit().putString("SAVED_DEVICE_ID", ownDeviceId).apply();
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting device name from settings", e);
        }

        // Try Settings.Secure
        try {
            String deviceName = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceName != null && !deviceName.isEmpty()) {
                ownDeviceId = "Device_" + deviceName.substring(0, Math.min(8, deviceName.length()));
                Log.d(TAG, "✓ Device ID from Secure ID: " + ownDeviceId);
                prefs.edit().putString("SAVED_DEVICE_ID", ownDeviceId).apply();
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting secure ID", e);
        }

        // FAIL - Cannot get device name
        Log.e(TAG, "❌ FAILED to initialize device name!");
        Log.e(TAG, "Please set your Bluetooth device name manually in Android settings");
        ownDeviceId = "02:00:00:00:00:00"; // Keep as invalid marker

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "        DTN APP STARTING");
        Log.d(TAG, "═══════════════════════════════════════");

        // Load saved preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentProtocol = prefs.getString(KEY_ROUTING_PROTOCOL, "EPIDEMIC");
        String savedTransport = prefs.getString(TRANSPORT_PREF, "WIFI_DIRECT");
        activeTransport = TransportType.valueOf(savedTransport);


        initializeUI();
        updateProtocolDisplay();
        updateTransportDisplay();
        setupPrioritySpinner();

        initializeDatabase();
        Log.d(TAG, "✅ Database initialized");

        initializeMyDeviceName();
        if (ownDeviceId != null && !ownDeviceId.isEmpty()) {
            isDeviceIdReady = true;
            Log.d(TAG, "✅ Device ID ready: " + ownDeviceId);
        } else {
            Log.w(TAG, "⚠️ Device ID not initialized properly");
        }

        logger = Logger.getInstance(getApplicationContext());
        initializeHandler();
        setListeners();
        Log.d(TAG, "✅ Core components initialized");


        initializeWifiDirectManagers();
        initializeBluetoothAdapter();
        Log.d(TAG, "✅ Transport adapters initialized");


        selectBestTransport();
        Log.d(TAG, "✅ Selected transport: " + activeTransport);

        selectBestTransport();
        Log.d(TAG, "✅ Selected transport: " + activeTransport);

        if (!hasAllRequiredPermissions()) {
            Log.d(TAG, "Permissions not granted, will request in onResume");
        } else {
            Log.d(TAG, "✅ All permissions already granted");
            isInitialized = true; // Mark as initialized
        }

    }

    private String getMyDeviceName() {
        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            Log.e(TAG, "Device name not initialized!");
            initializeMyDeviceName();
        }
        return ownDeviceId;
    }

    /**
     * Initialize Wi-Fi Direct managers only (no discovery)
     */
    private void initializeWifiDirectManagers() {
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.w(TAG, "Wi-Fi Direct not supported");
            return;
        }

        channel = manager.initialize(this, getMainLooper(), null);
        if (channel == null) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct channel");
            return;
        }

        // Create broadcast receiver (but don't register yet)
        wifiDirectBroadcastReceiver = new WifiDirectBroadcastReceiver(manager, channel, this);

        // Set up intent filters
        wifiDirectIntentFilter = new IntentFilter();
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        Log.d(TAG, "Wi-Fi Direct managers initialized (not started)");
    }

    /**
     *  Initialize Bluetooth adapter only (no server thread)
     */
    private void initializeBluetoothAdapter() {
        Log.d(TAG, "Initializing Bluetooth adapter...");

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth not available");
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "✓ Bluetooth adapter found");

        // Create broadcast receiver (but don't register yet)
        bluetoothReceiver = new BluetoothBroadcastReceiver(this);

        // Set up intent filters
        bluetoothIntentFilter = new IntentFilter();
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        Log.d(TAG, "✓ Bluetooth adapter initialized (server not started)");
    }


    /**
     * Called after permissions are granted to complete initialization
     */
    private void completeInitialization() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping");
            return;
        }

        Log.d(TAG, "╔═══════════════════════════════════════╗");
        Log.d(TAG, "  Completing initialization...");
        Log.d(TAG, "╚═══════════════════════════════════════╝");
        try {
            initializeMyDeviceName();

            if (ownDeviceId == null || ownDeviceId.equals("02:00:00:00:00:00")) {
                Log.e(TAG, "❌ Device name initialization FAILED");
                Toast.makeText(this, "ERROR: Cannot get device name. Please set Bluetooth name in Settings.", Toast.LENGTH_LONG).show();
                // DO NOT RETURN - let user know but continue
            } else {
                Log.d(TAG, "✓ Device name ready: " + ownDeviceId);
                isDeviceIdReady = true;
            }

            // ✅ STEP 1: Register broadcast receivers (need permissions)
            if (!isBluetoothReceiverRegistered && bluetoothReceiver != null && bluetoothIntentFilter != null) {
                registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
                isBluetoothReceiverRegistered = true;
                Log.d(TAG, "✓ Bluetooth receiver registered");
            }

            if (!isWifiDirectReceiverRegistered && wifiDirectBroadcastReceiver != null && wifiDirectIntentFilter != null) {
                registerReceiver(wifiDirectBroadcastReceiver, wifiDirectIntentFilter);
                isWifiDirectReceiverRegistered = true;
                Log.d(TAG, "✓ Wi-Fi Direct receiver registered");
            }

            // ✅ STEP 2: Start Bluetooth services (if Bluetooth is active transport)
            if (activeTransport == TransportType.BLUETOOTH) {
                // Check if Bluetooth is enabled
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    // Start server thread NOW (with permissions)
                    startBluetoothServerThread();

                    // Make discoverable
                    if (!isDiscoverableDialogShown) {
                        makeBluetoothDiscoverable();
                        isDiscoverableDialogShown = true;
                    }

                    Log.d(TAG, "✓ Bluetooth services started");
                } else {
                    Log.w(TAG, "Bluetooth not enabled, requesting...");
                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    enableBluetoothLauncher.launch(enableIntent);
                }
            }

            // ✅ STEP 3: Start automatic mesh mode
            startAutomaticMeshMode();

            // ✅ STEP 4: Load existing messages
            loadMessagesFromDatabase();

            // ✅ STEP 5: Update status
            runOnUiThread(() -> {
                statusTextView.setText("Status: Ready");
                statusTextView.setTextColor(0xFF4CAF50); // Green
            });

            isInitialized = true;
            Log.d(TAG, "✅ Initialization complete");
            Log.d(TAG, "╔═══════════════════════════════════════╗");

        } catch (SecurityException e) {
            Log.e(TAG, "❌ Security exception during initialization", e);
            Toast.makeText(this, "Permission error: " + e.getMessage(), Toast.LENGTH_LONG).show();

            // Request permissions again
            checkAndRequestPermissions();
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during initialization", e);
            Toast.makeText(this, "Initialization error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    //  REQUEST ALL THE PERMISSIONS REQUIRED
    private void requestPermissions() {
        Log.d(TAG, "Checking permissions...");

        List<String> permissionsToRequest = new ArrayList<>();

        // Location (CRITICAL)
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            Log.d(TAG, "Need ACCESS_FINE_LOCATION");
        }

        // Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }

        // Wi-Fi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting " + permissionsToRequest.size() + " permissions");
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
        } else {
            Log.d(TAG, "✅ All permissions granted");
            discoverPeers();
        }
    }

    /**
     * Show explanation dialog before requesting permissions
     */
    // Show explanation dialog before requesting
    private void showPermissionExplanationDialog(List<String> permissions) {
        StringBuilder message = new StringBuilder();
        message.append("This DTN Mesh App requires the following permissions:\n\n");

        for (String permission : permissions) {
            if (permission.contains("LOCATION")) {
                message.append("• Location Access\n  (Required for Wi-Fi Direct and Bluetooth discovery)\n\n");
            } else if (permission.contains("BLUETOOTH_SCAN")) {
                message.append("• Bluetooth Scan\n  (Find nearby devices)\n\n");
            } else if (permission.contains("BLUETOOTH_CONNECT")) {
                message.append("• Bluetooth Connect\n  (Connect to devices)\n\n");
            } else if (permission.contains("BLUETOOTH_ADVERTISE")) {
                message.append("• Bluetooth Advertise\n  (Make device discoverable)\n\n");
            } else if (permission.contains("NEARBY_WIFI_DEVICES")) {
                message.append("• Nearby Wi-Fi Devices\n  (Wi-Fi Direct networking)\n\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Permissions Needed")
                .setMessage(message.toString())
                .setPositiveButton("Continue", (dialog, which) -> {
                    // NOW request permissions
                    ActivityCompat.requestPermissions(
                            this,
                            permissions.toArray(new String[0]),
                            REQUEST_PERMISSIONS
                    );
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    Toast.makeText(this, "Cannot proceed without permissions", Toast.LENGTH_LONG).show();
                    finish();
                })
                .setCancelable(false)
                .show();
    }
    /**
     * Check if all critical permissions are granted
     */
    private boolean hasAllRequiredPermissions() {
        // Check location (critical)
        boolean hasLocation = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasLocation) {
            Log.e(TAG, "❌ Missing ACCESS_FINE_LOCATION");
            return false;
        }

        // Check Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasBtScan = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasBtConnect = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

            if (!hasBtScan || !hasBtConnect) {
                Log.e(TAG, "❌ Missing Bluetooth permissions");
                return false;
            }
        }

        Log.d(TAG, "✅ All required permissions granted");
        return true;
    }
    /**
     * Call this from onCreate AFTER UI initialization
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        } else {
            // For older Android versions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }


        // Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }
        // Wi-Fi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting " + permissionsToRequest.size() + " permissions");
            // Show explanation FIRST
            showPermissionExplanationDialog(permissionsToRequest);
        } else {
            Log.d(TAG, "All permissions already granted");
            completeInitialization();
        }
    }

    private void initializeUI() {
        statusTextView = findViewById(R.id.statusTextView);
        protocolTextView = findViewById(R.id.protocolTextView);
        transportTextView = findViewById(R.id.transportTextView);
        peerListView = findViewById(R.id.peerListView);
        chatListView = findViewById(R.id.chatListView);
        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);
        prioritySpinner = findViewById(R.id.prioritySpinner);
        toolbar = findViewById(R.id.toolbar);

        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);

        statusTextView.setText("Status: Disconnected");
        statusTextView.setTextColor(0xFFF44336);
    }

    private void updateProtocolDisplay() {
        if (currentProtocol.equals("SPRAY_AND_WAIT")) {
            protocolTextView.setText("Protocol: Spray and Wait 🔵");
            protocolTextView.setTextColor(0xFF1976D2);
        } else {
            protocolTextView.setText("Protocol: Epidemic Routing 🟠");
            protocolTextView.setTextColor(0xFFFF6F00);
        }
    }

    private void updateTransportDisplay() {
        if (activeTransport == TransportType.WIFI_DIRECT) {
            transportTextView.setText("Transport: Wi-Fi Direct 📡 (Fast)");
            transportTextView.setTextColor(0xFF4CAF50);
        } else {
            transportTextView.setText("Transport: Bluetooth 📱 (Compatible)");
            transportTextView.setTextColor(0xFF2196F3);
        }
        Log.d(TAG, "Active transport: " + activeTransport);
    }

    private void setupPrioritySpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.priority_options,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prioritySpinner.setAdapter(adapter);
        prioritySpinner.setSelection(0);
    }

    private void initializeWifiDirect() {
        // 1. Gets WifiP2pManager from system
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.w(TAG, "Wi-Fi Direct not supported");
            return;
        }

        // 2. Initializes Channel for communication
        channel = manager.initialize(this, getMainLooper(), null);
        if (channel == null) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct channel");
            return;
        }

        // 3. Creates WifiDirectBroadcastReceiver
        wifiDirectBroadcastReceiver = new WifiDirectBroadcastReceiver(manager, channel, this);
        // 4. Sets up intent filters for broadcasts
        wifiDirectIntentFilter = new IntentFilter();
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        Log.d(TAG, "Wi-Fi Direct initialized");
    }

    private void selectBestTransport() {
        boolean wifiDirectAvailable = isWifiDirectAvailable();
        boolean bluetoothAvailable = bluetoothAdapter != null;

        Log.d(TAG, "Wi-Fi Direct available: " + wifiDirectAvailable);
        Log.d(TAG, "Bluetooth available: " + bluetoothAvailable);

        if (wifiDirectAvailable) { // if (Wi-Fi Direct available)
            activeTransport = TransportType.WIFI_DIRECT;  //     activeTransport = WIFI_DIRECT  ← Preferred (faster)
            Log.d(TAG, "✓ Selected transport: Wi-Fi Direct");
        } else if (bluetoothAvailable) { // else if (Bluetooth available)
            activeTransport = TransportType.BLUETOOTH; //     activeTransport = BLUETOOTH  ← Fallback
            Log.d(TAG, "⚠️ Wi-Fi Direct unavailable, using Bluetooth");
            Toast.makeText(this, "Using Bluetooth (Wi-Fi Direct unavailable)", Toast.LENGTH_LONG).show();
        } else {
            Log.e(TAG, "❌ No transport available!");
            Toast.makeText(this, "No transport available!", Toast.LENGTH_LONG).show();
        }

        updateTransportDisplay();
        saveTransportPreference();
    }

    private boolean isWifiDirectAvailable() {
        if (manager == null) return false;
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    private void saveTransportPreference() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(TRANSPORT_PREF, activeTransport.toString());
        editor.apply();
    }

    private void initializeDatabase() {
        database = AppDatabase.getDatabase(getApplicationContext());
        messageDao = database.messageDao();
        friendDao = database.friendDao();
    }

    private void initializeHandler() {
        // Handler receives messages from background threads
        handler = new Handler(Looper.getMainLooper(), msg -> {


            // FROM WI-FI DIRECT THREADS
            if (msg.what == ServerThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Wi-Fi Direct ServerThread");

                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    Log.d(TAG, "Handler received (Wi-Fi Direct): " + receivedMessage.message_id);
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }

            // FROM BLUETOOTH THREADS
            if (msg.what == BluetoothClientThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Bluetooth ClientThread");

                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    Log.d(TAG, "Handler received (Bluetooth): " + receivedMessage.message_id);
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }
            if (msg.what == BluetoothServerThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Bluetooth ServerThread");

                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    Log.d(TAG, "Handler received (Bluetooth): " + receivedMessage.message_id);
                    handleReceivedMessage(receivedMessage);  // Same handler!
                }
                return true;
            }

            if (msg.what == BluetoothServerThread.MESSAGE_CONNECTION_ESTABLISHED) {
                String deviceName = (String) msg.obj ;

                Log.d(TAG, "✓ Handler: Connection established with " + deviceName);

                isBluetoothConnected = true;

                runOnUiThread(() -> {
                    updateConnectionStatus();
                    Toast.makeText(MainActivity.this,
                            "✓ Connected to " + deviceName,
                            Toast.LENGTH_SHORT).show();
                });

                return true;
            }

            if (msg.what == MESSAGE_CONNECTION_LOST) {
                long now = System.currentTimeMillis();
                if (now - lastConnectionLostTime < 1000) {
                    Log.d(TAG, "Ignoring duplicate CONNECTION_LOST (within 1s)");
                    return true;
                }
                lastConnectionLostTime = now;
                Log.w(TAG, ">>> MESSAGE_CONNECTION_LOST received!");

                // ✅ FIX: Extract device address from message
                String lostDeviceAddress = null ;
                if (msg.obj instanceof String) {
                    lostDeviceAddress = (String) msg.obj;
                }
                final String finalLostDeviceAddress = lostDeviceAddress;
                // ✅ MESH : Remove only the lost connection, not all
                if (lostDeviceAddress != null) {
                    bluetoothClientThreads.removeIf(thread ->
                            finalLostDeviceAddress.equals(thread.getRemoteDeviceAddress())
                    );
                    connectedDeviceAddresses.remove(lostDeviceAddress);
                }

                // Update connection status
                int remainingConnections = bluetoothClientThreads.size() +
                        (bluetoothServerThread != null ? bluetoothServerThread.getConnectedClientCount() : 0);

                if (remainingConnections == 0) {
                    isBluetoothConnected = false;
                    connectedBluetoothDeviceName = null;
                }

                runOnUiThread(() -> {
                    updateConnectionStatus();
                    Toast.makeText(MainActivity.this,
                            "Connection lost. Active: " + remainingConnections,
                            Toast.LENGTH_SHORT).show();
                });

                // ✅ MESH FIX: Try to maintain mesh density
                if (remainingConnections < MIN_MESH_CONNECTIONS) {
                    Log.d(TAG, "Mesh density low, starting discovery...");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startBluetoothDiscovery();
                    }, 2000);
                }

                return true;
            }
            return false;
        });
    }

    private void setListeners() {
        // -------------------- LONG CLICK: Add Friend --------------------
        peerListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (activeTransport == TransportType.WIFI_DIRECT) {
                if (deviceArray == null || position >= deviceArray.length) return false;
                final WifiP2pDevice device = deviceArray[position];
                new AlertDialog.Builder(this)
                        .setTitle("Add Friend")
                        .setMessage("Add " + device.deviceName + " as friend?")
                        .setPositiveButton("Yes", (dialog, which) ->
                                addFriend(device.deviceAddress, device.deviceName))
                        .setNegativeButton("No", null)
                        .show();
            } else {
                if (discoveredDevices == null || position >= discoveredDevices.size()) {
                    Toast.makeText(this, "Invalid selection", Toast.LENGTH_SHORT).show();
                    return false;
                }

                final BluetoothDevice device = discoveredDevices.get(position);
                String deviceName = device.getName() != null ? device.getName() : "Unknown Device";
                new AlertDialog.Builder(this)
                        .setTitle("Add Friend")
                        .setMessage("Add " + deviceName + " as friend?")
                        .setPositiveButton("Yes", (dialog, which) ->
                                addFriend(device.getAddress(), device.getName()))
                        .setNegativeButton("No", null)
                        .show();
            }
            return true;
        });


        // -------------------- CLICK: Connect to Peer --------------------
        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            Log.d(TAG, "=== Peer List Item Clicked ===");
            Log.d(TAG, "Position: " + position);
            Log.d(TAG, "Active Transport: " + activeTransport);

            if (activeTransport == TransportType.WIFI_DIRECT) {
                connectToWifiDirectDevice(position);

            } else { // BLUETOOTH
                Log.d(TAG, "Attempting Bluetooth connection...");

                //  null checks
                if (discoveredDevices == null) {
                    Log.e(TAG, "discoveredDevices is NULL");
                    Toast.makeText(this, "No devices discovered", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (discoveredDevices.isEmpty()) {
                    Log.e(TAG, "discoveredDevices is EMPTY");
                    Toast.makeText(this, "No devices available", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (position < 0 || position >= discoveredDevices.size()) {
                    Log.e(TAG, "Invalid position: " + position + ", size: " + discoveredDevices.size());
                    Toast.makeText(this, "Invalid device selection", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Get device from list
                BluetoothDevice device = discoveredDevices.get(position);

                if (device == null) {
                    Log.e(TAG, "Device at position " + position + " is NULL");
                    Toast.makeText(this, "Invalid device", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Get device name safely
                String deviceName = getBluetoothDeviceName(device);
                Log.d(TAG, "Selected device: " + deviceName + " (" + device.getAddress() + ")");

                // Show confirmation and connect
                Toast.makeText(this, "Connecting to " + deviceName + "...", Toast.LENGTH_SHORT).show();
                connectToBluetoothDevice(device);
            }
        });

        sendButton.setOnClickListener(v -> sendMessage());
    }

    private void connectToWifiDirectDevice(int position) {
        if (deviceArray == null || position >= deviceArray.length) {
            Toast.makeText(this, "Invalid peer selection", Toast.LENGTH_SHORT).show();
            return;
        }

        final WifiP2pDevice device = deviceArray[position];
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Log.d(TAG, "Connecting to (Wi-Fi Direct): " + device.deviceName);
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Toast.makeText(getApplicationContext(), "Connecting to " + device.deviceName, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Wi-Fi Direct connection initiated");
            }

            @Override
            public void onFailure(int reason) {
                String failureReason = getConnectionFailureReason(reason);
                Toast.makeText(getApplicationContext(), "Connection failed: " + failureReason, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Wi-Fi Direct connection failed: " + failureReason);
            }
        });
    }

    private void sendMessage() {
        String msgText = messageEditText.getText().toString().trim();

        if (msgText.isEmpty()) {
            Toast.makeText(this, "Message is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        // Declare it before the condition
        String targetDevice = null;

        if (activeTransport == TransportType.WIFI_DIRECT) {
            // Wi-Fi Direct mode
            Log.d(TAG, "Sending via Wi-Fi Direct");

            if (serverThread == null && clientThread == null) {
                Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
                return;
            }

            if (deviceArray == null || deviceArray.length == 0) {
                Toast.makeText(this, "No destination peer available", Toast.LENGTH_SHORT).show();
                return;
            }
            targetDevice = deviceArray[0].deviceName;
        }else if (activeTransport == TransportType.BLUETOOTH) {
            Log.d(TAG, "Sending via Bluetooth");

            if (!isBluetoothConnected) {
                Toast.makeText(this, "Not connected via Bluetooth", Toast.LENGTH_SHORT).show();
                return;
            }

            if (bluetoothClientThread == null && bluetoothServerThread == null) {
                Toast.makeText(this, "Bluetooth threads not initialized", Toast.LENGTH_SHORT).show();
                return;
            }

            // If connected as client, use remote device name (normalized)
            if (bluetoothClientThread != null) {
                BluetoothDevice remote = bluetoothClientThread.getRemoteDevice();
                if (remote != null) {
                    targetDevice = remote.getName();
                    Log.d(TAG, "Target device (client, name): " + targetDevice);
                }
            }

            // If server, get the first client's name (normalized)
            if (targetDevice == null || targetDevice.isEmpty()) {
                targetDevice = bluetoothServerThread.getFirstClientAddress(); // returns normalized name
                if (targetDevice != null) {
                    Log.d(TAG, "Target device (from server, name): " + targetDevice);
                }
            }
        }

        if (targetDevice == null) {
            Toast.makeText(this, "Cannot determine target device address", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "No target device found. Client thread: " + (bluetoothClientThread != null) +
                    ", Server thread: " + (bluetoothServerThread != null));
            return;
        }

        // Device id is ready
        actuallyTransmitMessage(msgText, targetDevice);
    }

    private void actuallyTransmitMessage(String msgText, String destinationId) {
        final Message message = new Message();

        // Display immediately
        String displayText = String.format(Locale.US, "Me: %s", msgText);
        ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, false);
        chatMessages.add(chatMessage);
        chatAdapter.notifyDataSetChanged();
        chatListView.smoothScrollToPosition(chatMessages.size() - 1);
        messageEditText.setText("");

        // Process on background thread
        executor.execute(() -> {
            try {
                message.message_id = UUID.randomUUID().toString();
                message.encrypted_payload = CryptoUtils.encrypt(msgText);
                message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);
                message.source_id = getMyDeviceName();
                message.destination_id = destinationId;
                message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH Priority") ? 1 : 0;
                message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000);
                message.hop_count = 0;
                message.copy_count = currentProtocol.equals("SPRAY_AND_WAIT") ? 6 : 1;

                messageDao.insert(message);
                Log.d(TAG, "Message saved: " + message.message_id);

                int devicesSentTo= 0 ;
                boolean sent = false;

                if (activeTransport == TransportType.BLUETOOTH) {
                    Log.d(TAG, "📡 Broadcasting via Bluetooth...");

                    if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                        try {
                            bluetoothServerThread.broadcastToAll(message);

                            // Get number of clients
                            devicesSentTo+= bluetoothServerThread.getConnectedClientCount(); // At least to the server's connected client(s)
                            sent = true;
                            Log.d(TAG, "✓ Broadcasted via Bluetooth ServerThread");

                        } catch (Exception e) {
                            Log.e(TAG, "Error broadcasting via server", e);
                        }
                    }

                    // ALSO SEND VIA CLIENT CONNECTION (If we initiated connection)
                    if (bluetoothClientThread != null && bluetoothClientThread.isAlive() &&
                            bluetoothClientThread.isConnected()) {
                        try {
                            bluetoothClientThread.write(message);
                            devicesSentTo++;
                            sent = true;
                            Log.d(TAG, "✓ Sent via Bluetooth ClientThread");

                        } catch (Exception e) {
                            Log.e(TAG, "Error sending via client", e);
                        }
                    }

                    if (!sent) {
                        Log.w(TAG, "⚠️ No active Bluetooth connections");
                    }

                } else if (activeTransport == TransportType.WIFI_DIRECT) {
                    // Wi-Fi Direct transmission
                    Log.d(TAG, "Attempting Wi-Fi Direct transmission...");

                    if (serverThread != null && serverThread.isConnected()) {
                        serverThread.write(message);
                        sent = true;
                        Log.d(TAG, "✓ Sent via Wi-Fi Direct ServerThread");

                    } else if (clientThread != null && clientThread.isConnected()) {
                        clientThread.write(message);
                        sent = true;
                        Log.d(TAG, "✓ Sent via Wi-Fi Direct ClientThread");

                    } else {
                        Log.e(TAG, "Wi-Fi Direct threads not available");
                    }
                }

                final int finalDevicesSentTo = devicesSentTo;

                if (sent) {
                    Log.d(TAG, "✅✅✅ Message transmitted successfully!");
                    Log.d(TAG, "  → Message ID: " + message.message_id);
                    Log.d(TAG, "  → Sent to " + devicesSentTo + " device(s)");
                    Log.d(TAG, "  → Destination: " + destinationId);
                    Log.d(TAG, "  → Protocol: " + currentProtocol);
                    Log.d(TAG, "  → Transport: " + activeTransport);

                    // Update UI on main thread
                    runOnUiThread(() -> {
                        String successMsg = finalDevicesSentTo > 1
                                ? "✓ Broadcasted to " + finalDevicesSentTo + " devices"
                                : "✓ Message sent";
                        Toast.makeText(MainActivity.this, successMsg, Toast.LENGTH_SHORT).show();
                    });

                } else {
                    Log.e(TAG, "❌ Failed to transmit message - no active connections");
                    Log.e(TAG, "  → Message stored in database for later forwarding");
                    Log.e(TAG, "  → Message ID: " + message.message_id);

                    // Update UI on main thread
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "No active connection - message queued for forwarding",
                                Toast.LENGTH_LONG).show();
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error sending message", e);
                e.printStackTrace();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Error: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

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

    public final WifiP2pManager.PeerListListener peerListListener = peerList -> {
        if (!peerList.getDeviceList().equals(peers)) {
            peers.clear();
            peers.addAll(peerList.getDeviceList());

            deviceNameArray = new String[peerList.getDeviceList().size()];
            deviceArray = new WifiP2pDevice[peerList.getDeviceList().size()];

            int index = 0;
            for (WifiP2pDevice device : peerList.getDeviceList()) {
                deviceNameArray[index] = device.deviceName;
                deviceArray[index] = device;
                index++;
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(getApplicationContext(),
                    android.R.layout.simple_list_item_1, deviceNameArray);
            peerListView.setAdapter(adapter);

            Log.d(TAG, "Discovered " + peers.size() + " Wi-Fi Direct peer(s)");
        }
    };

    public final WifiP2pManager.ConnectionInfoListener connectionInfoListener = info -> {
        // INITIAL LOGS
        Log.d(TAG, "=== Connection Info ===");
        Log.d(TAG, "Group Formed: " + info.groupFormed);
        Log.d(TAG, "Is Group Owner: " + info.isGroupOwner);
        Log.d(TAG, "Group Owner IP: " + (info.groupOwnerAddress != null ?
                info.groupOwnerAddress.getHostAddress() : "null"));

        // STORES IP OF THE GROUP OWNER
        final InetAddress groupOwnerAddress = info.groupOwnerAddress;

        if (info.groupFormed) {
            Log.d(TAG, "Connection established - updating device ID...");
            Log.d(TAG, "BEFORE: ownDeviceId = " + ownDeviceId);

            if (manager != null && channel != null) {
                manager.requestDeviceInfo(channel, device -> {
                    if (device != null) {
                        ownDeviceId = getMyDeviceName();
                        Log.d(TAG, "✅ Device ID confirmed: " + ownDeviceId);
                    }
                });
            }

            Log.d(TAG, "AFTER: ownDeviceId = " + ownDeviceId);
            // ═══════════════════════════════════════════════════════════════════
            // SET FLAG: Device ID is now ready!
            // ═══════════════════════════════════════════════════════════════════

            if (ownDeviceId != null && !ownDeviceId.isEmpty() && !ownDeviceId.contains("-")) {
                isDeviceIdReady = true;
                Log.d(TAG, "✅ isDeviceIdReady = true");

                // ═══════════════════════════════════════════════════════════════════
                // TRANSMIT ALL QUEUED MESSAGES!
                // ═══════════════════════════════════════════════════════════════════

                Log.d(TAG, "Processing " + pendingMessages.size() + " queued messages");
                while (!pendingMessages.isEmpty()) {
                    PendingMessage pending = pendingMessages.poll();
                    if (pending != null) {
                        Log.d(TAG, "Transmitting queued message to: " + pending.destination);
                        actuallyTransmitMessage(pending.text, pending.destination);
                    }
                }
            }else {
                // Device ID is still UUID - set flag anyway for research mode
                isDeviceIdReady = true;
                Log.d(TAG, "⚠️ Device ID is UUID, but setting isDeviceIdReady=TRUE for research mode");

                // Transmit queued messages anyway
                Log.d(TAG, "Processing " + pendingMessages.size() + " queued messages (research mode)");
                while (!pendingMessages.isEmpty()) {
                    PendingMessage pending = pendingMessages.poll();
                    if (pending != null) {
                        Log.d(TAG, "Transmitting queued message (UUID mode): " + pending.text);
                        actuallyTransmitMessage(pending.text, pending.destination);
                    }
                }
            }
        }

        //start thread
        if (info.groupFormed && info.isGroupOwner) {
            // ========== GROUP OWNER (Host) ==========
            if (serverThread == null || !serverThread.isAlive()) {
                Log.d(TAG, "✅ Starting ServerThread (Group Owner)");
                statusTextView.setText("Status: Connected as Host");
                statusTextView.setTextColor(0xFF4CAF50);
                // creates new server socket listening on port 8888 and waits for client connection
                serverThread = new ServerThread(handler);
                serverThread.start();

                // forwarding logic after 2 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    WifiP2pDevice connectedPeer = findConnectedPeer();
                    if (connectedPeer != null) {
                        Log.d(TAG, "Found connected peer: " + connectedPeer.deviceName);
                        triggerForwardingLogic(connectedPeer);
                    }
                }, 2000);
            }
            // check if device is client
        } else if (info.groupFormed) {
            // ========== CLIENT ==========
            if (clientThread == null || !clientThread.isAlive()) {
                Log.d(TAG, "✅ Starting ClientThread (Client connecting to host)");
                statusTextView.setText("Status: Connected as Client");
                statusTextView.setTextColor(0xFF4CAF50);

                //creates client socket connection to group owner
                clientThread = new ClientThread(groupOwnerAddress, handler);
                clientThread.start();

                // forwarding logic waits for 2 second
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    WifiP2pDevice connectedPeer = findConnectedPeer();
                    if (connectedPeer != null) {
                        Log.d(TAG, "Found connected peer: " + connectedPeer.deviceName);
                        triggerForwardingLogic(connectedPeer);
                    }
                }, 2000);
            }

            //clients also run server thread
            // for multi-hop DTN networks
            // client can receive relayed messages from other clients
            // enables mesh networking
            if (serverThread == null || !serverThread.isAlive()) {
                Log.d(TAG, "✅ Starting ServerThread (Client receiving relayed messages)");
                serverThread = new ServerThread(handler);
                serverThread.start();
                Log.d(TAG, "Client ServerThread started on port 8888 for relaying");
            }
        }
    };

    private void addFriend(final String deviceId, final String deviceName) {
        executor.execute(() -> {
            try {
                String friendId = deviceName;

                Friend existingFriend = friendDao.getFriendById(friendId);
                if (existingFriend == null) {
                    Friend newFriend = new Friend();
                    newFriend.deviceId = friendId;
                    newFriend.friendlyName = deviceName;
                    newFriend.lastEncounteredTimestamp = System.currentTimeMillis();

                    friendDao.insert(newFriend);

                    Log.d(TAG, "✓ Added friend: " + deviceName);

                    runOnUiThread(() -> Toast.makeText(MainActivity.this, deviceName +
                            " added as friend", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, deviceName +
                            " already a friend", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error adding Wi-Fi Direct friend", e);

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Error adding friend: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    public void onDisconnect() {
        Log.d(TAG, "Disconnected");
        statusTextView.setText("Status: Disconnected");
        statusTextView.setTextColor(0xFFF44336);

        if (serverThread != null) {
            serverThread.close();
            serverThread = null;
        }
        if (clientThread != null) {
            clientThread.close();
            clientThread = null;
        }
    }

    private WifiP2pDevice findConnectedPeer() {
        for (WifiP2pDevice peer : peers) {
            if (peer.status == WifiP2pDevice.CONNECTED) {
                return peer;
            }
        }
        return null;
    }

    private void triggerForwardingLogic(Object peer) {
        executor.execute(() -> {
            try {
                // Determine peer type and get ID
                String peerId;
                String peerName;
                boolean isBluetoothPeer = false;

                if (peer instanceof WifiP2pDevice) {
                    // Wi-Fi Direct
                    WifiP2pDevice wifiPeer = (WifiP2pDevice) peer;
                    peerId = wifiPeer.deviceName;
                    peerName = wifiPeer.deviceName;
                    Log.d(TAG, "Forwarding to Wi-Fi Direct peer: " + peerId);

                } else if (peer instanceof BluetoothDevice) {
                    // Bluetooth
                    BluetoothDevice btPeer = (BluetoothDevice) peer;
                    peerId = btPeer.getName();
                    peerName = btPeer.getName();
                    isBluetoothPeer = true;
                    Log.d(TAG, "Forwarding to Bluetooth peer: " + peerId);

                } else {
                    Log.e(TAG, "Unknown peer type");
                    return;
                }

                // Record friend encounter
                Friend connectedFriend = friendDao.getFriendById(peerId);
                if (connectedFriend == null) {
                    connectedFriend = new Friend();
                    connectedFriend.deviceId = peerId;
                    connectedFriend.friendlyName = peerName;
                    connectedFriend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.insert(connectedFriend);
                    Log.d(TAG, "✓ Created friend entry: " + peerName);
                } else {
                    // ✓ Update existing friend
                    connectedFriend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(connectedFriend);
                    Log.d(TAG, "✓ Updated friend encounter: " + peerName);
                }


                // Get all non-expired messages
                final List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                final List<Message> messagesToForward = new ArrayList<>();

                // Filter messages using contact history
                for (Message message : allMessages) {
                    // If message is FOR this device, send it
                    if (peerId.equals(message.destination_id)) {
                        messagesToForward.add(message);
                        Log.d(TAG, "Message " + message.message_id + " is FOR " + peerName);
                        continue;
                    }

                    // ✓ Forward all other messages
                   messagesToForward.add(message);
                    Log.d(TAG, "Adding message" + message.message_id + "for opportunistic forwarding");
                }
                // ✓ Check if there are messages to forward
                if (messagesToForward.isEmpty()) {
                    Log.d(TAG, "No messages to forward");
                    return;
                }
                Log.d(TAG, "Preparing to forward " + messagesToForward.size() + " messages");

                // Sort messages by priority and TTL
                Collections.sort(messagesToForward, (m1, m2) -> {
                    int priorityCompare = Integer.compare(m2.priority, m1.priority);
                    return priorityCompare != 0 ? priorityCompare : Long.compare(m2.ttl_timestamp, m1.ttl_timestamp);
                });

                // Initialize routing protocol
                if (activeRoutingProtocol == null) {
                    if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                        activeRoutingProtocol = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao);
                        sprayAndWaitRouting = (SprayAndWaitRouting) activeRoutingProtocol;
                        Log.d(TAG, "Initialized Spray-and-Wait routing");
                    } else {
                        activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId, messageDao);
                        epidemicRouting = (EpidemicRouting) activeRoutingProtocol;
                        Log.d(TAG, "Initialized Epidemic routing");
                    }
                }

                if (isBluetoothPeer) {
                    Log.d(TAG, "=== MESH FORWARDING TRIGGER ===");

                    // Collect all connected Bluetooth devices
                    List<BluetoothDevice> connectedBTDevices = new ArrayList<>();

                    // Add devices from client threads
                    for (BluetoothClientThread client : bluetoothClientThreads) {
                        if (client.isConnected()) {
                            connectedBTDevices.add(client.getRemoteDevice());
                        }
                    }

                    Log.d(TAG, "Found " + connectedBTDevices.size() + " connected BT devices");

                    if (connectedBTDevices.isEmpty()) {
                        Log.w(TAG, "No connected Bluetooth devices for mesh forwarding");
                        return;
                    }

                    // Forward using mesh-aware routing
                    if (currentProtocol.equals("EPIDEMIC")) {
                        if (epidemicRouting == null) {
                            epidemicRouting = new EpidemicRouting(getApplicationContext(), ownDeviceId, messageDao);
                        }

                        epidemicRouting.forwardMessagesToMultipleDevicesBluetooth(
                                messagesToForward,
                                connectedBTDevices,
                                bluetoothServerThread,
                                bluetoothClientThreads
                        );

                    } else if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                        if (sprayAndWaitRouting == null) {
                            sprayAndWaitRouting = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao);
                        }

                        sprayAndWaitRouting.forwardMessagesToMultipleDevicesBluetooth(
                                messagesToForward,
                                connectedBTDevices,
                                bluetoothServerThread,
                                bluetoothClientThreads
                        );
                    }

                    Log.d(TAG, "✓✓✓ Mesh forwarded " + messagesToForward.size() +
                            " messages to " + connectedBTDevices.size() + " devices");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in forwarding logic", e);
                e.printStackTrace();
            }
        });
    }

    private void loadMessagesFromDatabase() {
        executor.execute(() -> {
            try {
                List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                Log.d(TAG, "Found " + allMessages.size() + " messages in database");

                final String myDeviceId = getMyDeviceName();
                if (myDeviceId == null || myDeviceId.isEmpty()) {
                    Log.e(TAG, "Cannot load messages - device ID is null");
                    return;
                }

                Log.d(TAG, "My device ID: " + myDeviceId);

                runOnUiThread(() -> {
                    chatMessages.clear();

                    for (Message msg : allMessages) {
                        // ✓ NULL CHECKS FIRST
                        if (msg == null) {
                            Log.w(TAG, "Null message skipped");
                            continue;
                        }

                        if (msg.message_id == null || msg.source_id == null || msg.destination_id == null) {
                            Log.w(TAG, "Message with null fields skipped: " + msg.message_id);
                            continue;
                        }

                        // ✓ CHANGED: Use getMyDeviceName() instead of ownDeviceId
                        boolean isFromMe = msg.source_id.equals(myDeviceId);
                        boolean isForMe = msg.destination_id.equals(myDeviceId);

                        if (!isFromMe && !isForMe) {
                            Log.d(TAG, "Skipping transit message: " + msg.message_id);
                            continue;
                        }

                        try {
                            String decryptedText = CryptoUtils.decrypt(msg.encrypted_payload);

                            String displayText;
                            if (isFromMe) {
                                displayText = "Me: " + decryptedText;
                            } else {
                                displayText = msg.source_id + ": " + decryptedText;
                            }

                            ChatMessage chatMessage = new ChatMessage(
                                    displayText,
                                    msg.message_id,
                                    msg.is_delivered
                            );
                            chatMessages.add(chatMessage);
                            Log.d(TAG, "✓ Loaded: " + displayText);

                        } catch (Exception e) {
                            Log.e(TAG, "Error decrypting message: " + msg.message_id, e);
                        }
                    }

                    chatAdapter.notifyDataSetChanged();
                    if (!chatMessages.isEmpty()) {
                        chatListView.smoothScrollToPosition(chatMessages.size() - 1);
                    }

                    Log.d(TAG, "✓ Loaded " + chatMessages.size() + " messages");
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading messages", e);
                e.printStackTrace();
            }
        });
    }

    private void handleReceivedMessage(Message message) {
        // Process on background thread (database operations)
        executor.execute(() -> {
            try {
                Log.d(TAG, "=== Processing Received Message ===");
                Log.d(TAG, "Message ID: " + message.message_id);
                Log.d(TAG, "From: " + message.source_id);
                Log.d(TAG, "To: " + message.destination_id);

                // 1. VALIDATE CHECKSUM (Check for tampering)
                boolean checksumValid = CryptoUtils.validateChecksum(
                        message.encrypted_payload,
                        message.checksum
                );
                Log.d(TAG, "Checksum validation: " + checksumValid);

                if (!checksumValid) {
                    Log.e(TAG, "Checksum failed - message tampered!");
                    logger.logEvent("EVENT=MESSAGE_DROPPED_INVALID_CHECKSUM | MSG_ID=" +
                            message.message_id);
                    return;  // ← REJECT MESSAGE
                }

                // ✓ Checksum valid - message not tampered, continue processing
                if (ownDeviceId == null || ownDeviceId.isEmpty() || ownDeviceId.equals("02:00:00:00:00:00")) {
                    Log.w(TAG, "⚠️ Device ID not properly initialized: " + ownDeviceId);
                    Log.d(TAG, "Attempting to refresh device ID synchronously...");

                    // Wait synchronously for device ID update
//                    ensureDeviceIdIsSet();
                    Log.d(TAG, "Device ID after refresh: " + ownDeviceId);

                    if (ownDeviceId == null || ownDeviceId.equals("02:00:00:00:00:00")) {
                        Log.w(TAG, "⚠️ Still invalid - accepting message anyway for testing");
                        // For research/testing: accept message even with wrong ID
                    }
                }
                // Check message type FIRST, before processing
                if (message.message_type == Message.TYPE_ACK) {
                    Log.d(TAG, "Processing ACK message");
                    processAck(message);

                } else if (message.message_type == Message.TYPE_DATA) {
                    Log.d(TAG, "Processing DATA message");
                    processDataMessage(message);
                } else {
                    Log.w(TAG, "Unkown message type: " + message.message_type);
                }

            }catch (Exception e){
                Log.e(TAG,"Error handling message", e);
                logger.logEvent("EVENT=MESSAGE_PROCESSING_ERROR | MSG_ID"+
                        (message != null ? message.message_id : "unknown")+
                        " | ERROR=" + e.getMessage());
            }
        });
    }

    private void processDataMessage(Message message) throws Exception {
        Log.d(TAG, "Processing DATA message: " + message.message_id);

        if (message == null || message.message_id == null) {
            Log.e(TAG, "Invalid message - null or missing ID");
            return;
        }

        // CHECK FOR DUPLICATES
        Message existing = messageDao.getMessageById(message.message_id);
        if (existing != null) {
            Log.d(TAG, "Duplicate message received, ignoring");
            return; // ← Skip if already received
        }

        // SAVE TO DATABASE
        messageDao.insert(message);
        Log.d(TAG, "New message stored in database");

        String myDeviceId = getMyDeviceName();

        if (myDeviceId == null || myDeviceId.isEmpty()|| myDeviceId.equals("02:00:00:00:00:00")) {
            Log.e(TAG, "Cannot process message - device name not initialized");
            // Store for later processing
            return;
        }

        Log.d(TAG, "Checking destination...");
        Log.d(TAG, "  My device name: " + myDeviceId);
        Log.d(TAG, "  Message destination: " + message.destination_id);

        boolean isForMe = myDeviceId.equals(message.destination_id);

        if (isForMe) {
            // ✓ YES - MESSAGE IS FOR ME!
            Log.d(TAG, "✓ Message IS FOR ME!");

            if (message.encrypted_payload == null) {
                Log.e(TAG, "Message has null encrypted payload");
                return;
            }

            // DECRYPT MESSAGE
            String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
            Log.d(TAG, "✓ Decrypted: " + decryptedText);

            // DISPLAY IN CHAT (MAIN THREAD)
            runOnUiThread(() -> {
                String displayText = "Peer: " + decryptedText;
                Log.d(TAG, "Adding to UI: " + displayText);

                ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, true);
                chatMessages.add(chatMessage);
                chatAdapter.notifyDataSetChanged();
                chatListView.smoothScrollToPosition(chatMessages.size() - 1);

                Log.d(TAG, "Message displayed. Total: " + chatMessages.size());
                Toast.makeText(MainActivity.this, "📩 New message!", Toast.LENGTH_SHORT).show();
            });

            // Mark as delivered
            message.is_delivered = true;
            messageDao.update(message);

            // LOG DELIVERY
            logger.logEvent("EVENT=MESSAGE_DELIVERED | MSG_ID=" + message.message_id);

            // SEND ACK
            generateAndSendAck(message);

        } else {
            // ✗ NO - MESSAGE IS NOT FOR ME
            Log.d(TAG, "✗ Message NOT for me, forwarding..... ");
            Log.d(TAG, "  From: " + message.source_id);
            Log.d(TAG, "  To: " + message.destination_id);
            Log.d(TAG, "  Current hop: " + message.hop_count);

            // ✓ INCREMENT HOP COUNT
            message.hop_count++;

            if (message.hop_count >= 15) {
                Log.w(TAG, "Message exceeded hop limit, dropping: " + message.message_id);
                return;
            }

            messageDao.update(message);

            boolean forwarded = false;

            // ✓ Forward based on active transport
            if (bluetoothClientThreads != null && !bluetoothClientThreads.isEmpty()) {
                for (BluetoothClientThread client : bluetoothClientThreads) {
                    if (client != null && client.isConnected()) {
                        try {
                            client.write(message);
                            forwarded = true;
                            Log.d(TAG, "✅ Forwarded via Bluetooth ClientThread");
                        } catch (Exception e) {
                            Log.e(TAG, "Error forwarding via client", e);
                        }
                    }
                }
            }

            if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                try {
                    bluetoothServerThread.broadcastToAll(message);
                    forwarded = true;
                    Log.d(TAG, "✅ Forwarded via Bluetooth ServerThread");
                } catch (Exception e) {
                    Log.e(TAG, "Error forwarding via server", e);
                }
            } else if (activeTransport == TransportType.WIFI_DIRECT) {
                if (serverThread != null && serverThread.isConnected()) {
                    serverThread.write(message);
                    forwarded = true;
                    Log.d(TAG, "✓ Forwarded via Wi-Fi Server (hop " + message.hop_count + ")");
                }

                if (clientThread != null && clientThread.isConnected()) {
                    clientThread.write(message);
                    forwarded = true;
                    Log.d(TAG, "✓ Forwarded via Wi-Fi Client (hop " + message.hop_count + ")");
                }
            }

            if (!forwarded) {
                Log.w(TAG, "⚠️ Could not forward message - no active connections");
            }

        }
    }
    private void generateAndSendAck(Message originalMessage) throws Exception {
        // 1. CREATE ACK MESSAGE
        Message ackMessage = new Message();

        // TYPE_ACK = 1 (different from TYPE_DATA = 0)
        ackMessage.message_type = Message.TYPE_ACK;

        // Send ACK back to original sender
        // "Message came from Device A, send ACK to Device A"
        ackMessage.destination_id = originalMessage.source_id;
        // From me (Device B)
        ackMessage.source_id = ownDeviceId;

        // 2. ENCRYPT MESSAGE ID IN ACK PAYLOAD
        ackMessage.encrypted_payload = CryptoUtils.encrypt(originalMessage.message_id);
        ackMessage.checksum = CryptoUtils.generateChecksum(ackMessage.encrypted_payload);
        ackMessage.priority = 1;
        ackMessage.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000);
        ackMessage.hop_count = 0;
        ackMessage.copy_count = 1;

        // 3. SAVE ACK TO DATABASE
        messageDao.insert(ackMessage);
        logger.logEvent("EVENT=ACK_GENERATED | FOR_MSG_ID=" + originalMessage.message_id);

        // 4. SEND ACK BACK
        if (serverThread != null && serverThread.isConnected()) {
            serverThread.write(ackMessage);
        } else if (clientThread != null && clientThread.isConnected()) {
            clientThread.write(ackMessage);
        }
        Log.d(TAG, "ACK sent for message: " + originalMessage.message_id);
        logger.logEvent("EVENT=ACK_GENERATED | FOR_MSG_ID=" + originalMessage.message_id);
    }

    private void processAck(Message ackMessage) throws Exception {
        if (!ownDeviceId.equals(ackMessage.destination_id)) {
            Message existing = messageDao.getMessageById(ackMessage.message_id);
            if (existing == null) {
                messageDao.insert(ackMessage);

                WifiP2pDevice connectedPeer = findConnectedPeer();
                if (connectedPeer != null) {
                    triggerForwardingLogic(connectedPeer);
                }
            }
            return;
        }

        // FIXED: Added Context parameter
        String originalMessageId = CryptoUtils.decrypt(ackMessage.encrypted_payload);
        Message messageToUpdate = messageDao.getMessageById(originalMessageId);

        if (messageToUpdate != null && !messageToUpdate.is_delivered) {
            messageToUpdate.is_delivered = true;
            messageDao.update(messageToUpdate);
            logger.logEvent("EVENT=MESSAGE_DELIVERED_ACK_RECEIVED | MSG_ID=" + originalMessageId);

            runOnUiThread(() -> {
                for (ChatMessage cm : chatMessages) {
                    if (cm.messageId != null && cm.messageId.equals(originalMessageId)) {
                        cm.isDelivered = true;
                        break;
                    }
                }
                chatAdapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, "✓ Delivery Confirmed!", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void discoverPeers() {
        if (activeTransport == TransportType.WIFI_DIRECT) {
            discoverWifiDirectPeers();
        } else {
            startBluetoothDiscovery();
        }
    }

    private void discoverWifiDirectPeers() {
        if (manager == null || channel == null) {
            Toast.makeText(this, "Wi-Fi Direct not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Log.d(TAG, "Starting Wi-Fi Direct peer discovery");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discovery started");
                statusTextView.setText("Status: Discovering...");
                statusTextView.setTextColor(0xFFFFC107);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Discovery failed: " + reason);
                statusTextView.setText("Status: Discovery Failed");
            }
        });
    }

    private void initializeBluetooth() {
        Log.d(TAG, "Initializing Bluetooth...");

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth not available");
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "✓ Bluetooth adapter found");

        // create and register receiver
        bluetoothReceiver = new BluetoothBroadcastReceiver(this);
        bluetoothIntentFilter = new IntentFilter();
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
        isBluetoothReceiverRegistered = true;

        Log.d(TAG, "✓ Bluetooth receiver registered");

        // Check if enabled
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth disabled, requesting enable...");
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableIntent);
        } else {
            Log.d(TAG, "✓ Bluetooth enabled");
            startBluetoothServerThread();
        }
    }

    private void startBluetoothServerThread() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "❌ Cannot start server - Bluetooth not available or disabled");
            return;
        }

        // ✅ CHECK PERMISSION FIRST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ BLUETOOTH_CONNECT permission not granted");
                return;
            }
        }

        // Now safe to start server thread
        if (bluetoothServerThread == null || !bluetoothServerThread.isAlive()) {
            try {
                bluetoothServerThread = new BluetoothServerThread(bluetoothAdapter, handler);
                bluetoothServerThread.start();
                Log.d(TAG, "✓ Bluetooth server thread started");
            } catch (SecurityException e) {
                Log.e(TAG, "❌ Security exception starting server thread", e);
                Toast.makeText(this, "Cannot start Bluetooth server - permission denied", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting server thread", e);
            }
        } else {
            Log.d(TAG, "Bluetooth server thread already running");
        }
    }

    public void startBluetoothDiscovery() {
        Log.d(TAG, "Starting smart Bluetooth discovery...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {

                Log.e(TAG, "❌ Bluetooth permissions not granted");
                Toast.makeText(this, "Please grant Bluetooth permissions", Toast.LENGTH_LONG).show();
                requestPermissions();
                return;
            }
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableIntent);
            return;
        }
        // Reset receiver's discovery lists
        if (bluetoothReceiver != null) {
            bluetoothReceiver.resetDiscovery();
        }
        // Clear UI list
        discoveredDevices.clear();

        runOnUiThread(() -> {
            updateBluetoothDeviceList(new ArrayList<>());
            statusTextView.setText("Status: Scanning for devices...");
            statusTextView.setTextColor(0xFFFFC107); // Yellow
        });

        // Get local Bluetooth address ONCE
        String localAddress = null;
        try {
            localAddress = bluetoothAdapter.getAddress();
            Log.d(TAG, "Local Bluetooth address: " + localAddress);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot get local address", e);
        }

        // STRATEGY 1: Get Paired Devices
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            if (pairedDevices != null && !pairedDevices.isEmpty()) {
                Log.d(TAG, "✓ Found " + pairedDevices.size() + " paired device(s)");

                for (BluetoothDevice device : pairedDevices) {
                    String devName = device.getName() != null ? device.getName() : "Unknown";
                    Log.d(TAG, "  • Paired: " + devName + " (" + device.getAddress() + ")");

                    // Skip self
                    if (localAddress != null && device.getAddress().equals(localAddress)) {
                        Log.d(TAG, "    ✗ Skipping self device");
                        continue;
                    }

                    // Check if DTN compatible
                    if (isDTNDevice(device)) {
                        Log.d(TAG, "    ✓ Compatible device");

                        // Add to discovered list
                        if (!discoveredDevices.contains(device)) {
                            discoveredDevices.add(device);
                        }
                    }
                }

                // Update UI with paired devices
                if (!discoveredDevices.isEmpty()) {
                    updateBluetoothDeviceList(new ArrayList<>(discoveredDevices));

                    runOnUiThread(() -> {
                        statusTextView.setText("Status: Found " + discoveredDevices.size() + " paired device(s)");
                    });
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for paired devices", e);
        }

        // STRATEGY 2: Classic Bluetooth Discovery
        Log.d(TAG, "Starting active Bluetooth discovery...");

        if(discoveredDevices.size() < 2){
            try {
                // Cancel existing discovery
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                    Log.d(TAG, "Cancelled existing discovery");

                    // Wait briefly
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Start new discovery
                boolean started = bluetoothAdapter.startDiscovery();

                if (started) {
                    Log.d(TAG, "✓ Active discovery started");
                    runOnUiThread(() -> {
                        statusTextView.setText("Status: Scanning for new devices...");
                    });
                } else {
                    Log.w(TAG, "✗ Failed to start active discovery");
                }

            } catch (Exception e) {
                Log.e(TAG, "Active discovery failed", e);
            }
        }else {
            Log.d(TAG, "✅ Skipping active discovery - already have " + discoveredDevices.size() + " devices");
        }

        // STRATEGY 3: BLE Scan (if supported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "Starting BLE advertising and scan...");
            startBLEAdvertising();
            tryBLEScan();
        }
    }

    private void tryBLEScan() {
        Log.d(TAG, "Attempting BLE scan...");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        //Check Permission for BlE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_SCAN permission missing");
                return;
            }
        }

        try {
            BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();

            if (scanner == null) {
                Log.w(TAG, "BLE scanner not available");
                return;
            }

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)  // ✓ Report immediately
                    .build();

            // ✓ Store callback as instance variable so we can stop it later
            bleScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String deviceName = device.getName() != null ? device.getName() : "Unknown";
                    String deviceAddress = device.getAddress();

                    int rssi = result.getRssi();


                    if (!isDTNDevice(device)) {
                        return;
                    }

                    boolean alreadyAdded = false;
                    for (BluetoothDevice existing : discoveredDevices) {
                        if (existing.getAddress().equals(deviceAddress)) {
                            alreadyAdded = true;
                            break;
                        }
                    }

                    if (alreadyAdded) {
                        return;
                    }

                    discoveredDevices.add(device);

                    //update ui with the actual list
                    runOnUiThread(() -> {
                        List<BluetoothDevice> currentList = new ArrayList<>(discoveredDevices);
                        updateBluetoothDeviceList(currentList);

                        Toast.makeText(MainActivity.this,
                                "Found: " + deviceName,
                                Toast.LENGTH_SHORT).show();
                    });

                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "❌ BLE scan failed with error code: " + errorCode);

                    String errorMsg = "";
                    switch (errorCode) {
                        case SCAN_FAILED_ALREADY_STARTED:
                            errorMsg = "Scan already started";
                            break;
                        case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                            errorMsg = "App registration failed";
                            break;
                        case SCAN_FAILED_INTERNAL_ERROR:
                            errorMsg = "Internal error";
                            break;
                        case SCAN_FAILED_FEATURE_UNSUPPORTED:
                            errorMsg = "Feature unsupported";
                            break;
                        default:
                            errorMsg = "Unknown error (" + errorCode + ")";
                    }

                    Toast.makeText(MainActivity.this, "BLE scan failed: " + errorMsg, Toast.LENGTH_SHORT).show();
                }
            };

            scanner.startScan(null, settings, bleScanCallback);
            Log.d(TAG, "✓ BLE scan started");
            runOnUiThread(() -> {
                statusTextView.setText("Status: Scanning (BLE)...");
            });

            // Stop scan after 15 seconds to save battery
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (bleScanCallback != null) {
                        Log.d(TAG, "Stopping BLE scan (timeout)");
                        scanner.stopScan(bleScanCallback);
                        Log.d(TAG, "✓ BLE scan stopped (timeout)");

                        int totalDevices = discoveredDevices.size();

                        runOnUiThread(() -> {
                            if (totalDevices > 0) {
                                statusTextView.setText("Status: Found " + totalDevices + " device(s)");
                                Toast.makeText(MainActivity.this,
                                        "BLE scan complete: " + totalDevices + " devices",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                statusTextView.setText("Status: No BLE devices found");
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping BLE scan", e);
                }
            }, 15000);

        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied: " + e.getMessage());
            Toast.makeText(this, "BLE permission denied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "BLE scan error: " + e.getMessage());
            Log.e(TAG, "Exception details:", e);
        }
    }

    private void startBLEAdvertising() {
        Log.d(TAG, ">>> STARTING BLE ADVERTISING...");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "❌ Android < LOLLIPOP, BLE not supported");
            return;
        }
        Log.d(TAG, "✓ Android version OK");

        try {
            // Step 1: Get Bluetooth Manager
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                Log.e(TAG, "❌ BluetoothManager is null");
                return;
            }

            // Step 2: Get Bluetooth Adapter
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null) {
                Log.e(TAG, "❌ BluetoothAdapter is null");
                return;
            }

            // Step 3: Check Bluetooth enabled
            if (!adapter.isEnabled()) {
                Log.e(TAG, "❌ Bluetooth is NOT enabled");
                return;
            }

            // Step 4: Get BLE Advertiser
            BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.e(TAG, "❌ BluetoothLeAdvertiser is null - BLE NOT supported");
                return;
            }

            // Step 5: Check permissions (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "Checking BLUETOOTH_ADVERTISE permission (Android 12+)...");
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "❌ BLUETOOTH_ADVERTISE permission not granted");
                    return;
                }
            }

            // Step 6: Create Advertising Data
            AdvertiseData advertisingData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();

            // Step 7: Create Advertising Settings
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(2)
                    .setConnectable(true)
                    .build();

            advertiser.startAdvertising(settings, advertisingData, new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "✓✓✓ BLE ADVERTISING STARTED SUCCESSFULLY ✓✓✓");
                    Toast.makeText(MainActivity.this, "✓ BLE Advertising Active", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.e(TAG, "❌❌❌ BLE ADVERTISING FAILED ❌❌❌");
                    Log.e(TAG, "Error code: " + errorCode);

                    String errorMsg = "";
                    switch (errorCode) {
                        case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                            errorMsg = "ALREADY_STARTED (0)";
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                            errorMsg = "DATA_TOO_LARGE (1)";
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            errorMsg = "TOO_MANY_ADVERTISERS (2)";
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                            errorMsg = "INTERNAL_ERROR (3)";
                            break;
                        default:
                            errorMsg = "UNKNOWN (" + errorCode + ")";
                    }

                    Log.e(TAG, "Failure reason: " + errorMsg);
                    Toast.makeText(MainActivity.this, "BLE Advertising failed: " + errorMsg, Toast.LENGTH_LONG).show();
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "❌ SecurityException in BLE advertising", e);
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception in BLE advertising", e);
            e.printStackTrace();
        }
    }

    // Called when device is paired
    public void onBluetoothDevicePaired(BluetoothDevice device) {
        Log.d(TAG, "Device paired: " + device.getName());
//        connectToBluetoothDevice(device);
    }
    // Handle Bluetooth state changes
    public void onBluetoothStateChanged(boolean enabled) {
        Log.d(TAG, "onBluetoothStateChanged: " + (enabled ? "ON" : "OFF"));

        if (!enabled) {
            // ✓ BLUETOOTH TURNED OFF - DISCONNECT!
            Log.w(TAG, "Bluetooth is OFF");

            // ✓ CLEAR CONNECTION FLAGS
            isBluetoothConnected = false;
            connectedBluetoothDeviceName = null;

            // ✓ CLOSE THREADS
            if (bluetoothClientThread != null) {
                bluetoothClientThread.close();
                bluetoothClientThread = null;
            }

            if (bluetoothServerThread != null) {
                bluetoothServerThread.close();
                bluetoothServerThread = null;
            }

            // ✓ UPDATE UI
            runOnUiThread(() -> {
                statusTextView.setText("Status: Bluetooth OFF");
                statusTextView.setTextColor(0xFFF44336);  // Red
                Toast.makeText(MainActivity.this, "Bluetooth disabled", Toast.LENGTH_SHORT).show();
            });
        } else {
            // Bluetooth turned ON
            runOnUiThread(() -> {
                statusTextView.setText("Status: Bluetooth ON");
                statusTextView.setTextColor(0xFF4CAF50);  // Green
                Toast.makeText(MainActivity.this, "✓ Bluetooth enabled", Toast.LENGTH_SHORT).show();
            });
            startBluetoothDiscovery();
        }
    }

    // Connect to specific device
    public void connectToBluetoothDevice(final BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Device is null");
            return;
        }

        // Permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ BLUETOOTH_CONNECT permission NOT GRANTED!");
            runOnUiThread(() -> Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show());
            return;
        }

        final String deviceAddress = device.getAddress();
        final String deviceName = getBluetoothDeviceName(device);

        // Clean up dead connections first
        cleanupDeadConnections();

        // Check connection limit
        if (bluetoothClientThreads.size() >= MAX_CLIENT_CONNECTIONS) {
            Log.w(TAG, "Max client connections reached (" + MAX_CLIENT_CONNECTIONS + ")");
            runOnUiThread(() -> Toast.makeText(this, "Max connections reached", Toast.LENGTH_SHORT).show());
            return;
        }

        // Check if already connected
        if (isAlreadyConnectedTo(deviceAddress)) {
            Log.w(TAG, "Already connected to: " + deviceName);
            return;
        }

        // Check recent connection attempts
        Long lastAttempt = connectionAttempts.get(deviceAddress);
        if (lastAttempt != null && (System.currentTimeMillis() - lastAttempt) < CONNECTION_RETRY_DELAY) {
            Log.d(TAG, "Recent connection attempt to " + deviceName + ", skipping");
            return;
        }

        Log.d(TAG, "Connecting to " + deviceName + " @ " + deviceAddress);
        connectionAttempts.put(deviceAddress, System.currentTimeMillis());

        final int currentAttemptId = ++connectionAttemptId;

        // Ensure server thread is running
        if (bluetoothServerThread == null || !bluetoothServerThread.isAlive()) {
            startBluetoothServerThread();
        }

        // Create and add client thread
        BluetoothClientThread newClientThread = new BluetoothClientThread(device, handler);
        addBluetoothClient(newClientThread); // Use thread-safe method
        newClientThread.start();

        Log.d(TAG, "✅ Client thread started for " + deviceName + " (Total: " + bluetoothClientThreads.size() + ")");

        // Connection verification after delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (currentAttemptId != connectionAttemptId) {
                return;
            }

            boolean connected = isAlreadyConnectedTo(deviceAddress);

            if (connected) {
                Log.d(TAG, "✅✅✅ Connection SUCCESSFUL to " + deviceName);

                runOnUiThread(() -> {
                    updateConnectionStatus();
                    Toast.makeText(MainActivity.this,
                            "✅ Connected to " + deviceName, Toast.LENGTH_SHORT).show();
                });

                // Trigger forwarding
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isBluetoothConnected) {
                        triggerForwardingLogic(device);
                    }
                }, 2000);

            } else {
                Log.e(TAG, "❌❌❌ Connection FAILED to " + deviceName);

                // Remove failed connection
                removeBluetoothClient(deviceAddress); // Use thread-safe method

                runOnUiThread(() -> {
                    updateConnectionStatus();
                    Toast.makeText(MainActivity.this,
                            "Failed to connect to " + deviceName, Toast.LENGTH_SHORT).show();
                });
            }
        }, 3000);
    }


    // Simple callback - just request again if denied
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            List<String> deniedPermissions = new ArrayList<>();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    deniedPermissions.add(permissions[i]);
                }
            }

            if (allGranted) {
                Log.d(TAG, "All permissions granted!");
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
                completeInitialization();
            } else {
                Log.w(TAG, "Some permissions denied: " + deniedPermissions.size());
                handleDeniedPermissions(deniedPermissions);
            }
        }
    }
    private void handleDeniedPermissions(List<String> deniedPermissions) {
        // Check if any are permanently denied
        List<String> permanentlyDenied = new ArrayList<>();
        for (String permission : deniedPermissions) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                permanentlyDenied.add(permission);
            }
        }

        if (!permanentlyDenied.isEmpty()) {
            showPermissionSettingsDialog(permanentlyDenied);
        } else {
            // Show retry dialog
            new AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("The app needs all permissions to function. Please grant them.")
                    .setPositiveButton("Retry", (dialog, which) -> checkAndRequestPermissions())
                    .setNegativeButton("Exit", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        }
    }
    /**
     * Show dialog explaining why permissions are needed (user can retry)
     */
    private void showPermissionRationaleDialog(List<String> deniedPermissions) {
        StringBuilder message = new StringBuilder();
        message.append("This app needs the following permissions to work:\n\n");

        for (String permission : deniedPermissions) {
            if (permission.contains("LOCATION")) {
                message.append("📍 Location: Required for Wi-Fi Direct and Bluetooth device discovery\n\n");
            } else if (permission.contains("BLUETOOTH")) {
                message.append("📱 Bluetooth: Required for mesh networking\n\n");
            } else if (permission.contains("WIFI")) {
                message.append("📡 Wi-Fi: Required for Wi-Fi Direct connections\n\n");
            }
        }

        message.append("Without these permissions, the app cannot function.");

        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage(message.toString())
                .setPositiveButton("Grant Permissions", (dialog, which) -> {
                    Log.d(TAG, "User clicked Grant - requesting permissions again");
                    requestPermissions();
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    Log.d(TAG, "User declined permissions - exiting app");
                    Toast.makeText(this, "App cannot function without permissions", Toast.LENGTH_LONG).show();
                    finish();
                })
                .setCancelable(false)
                .show();
    }
    /**
     * Show dialog to open app settings (for permanently denied permissions)
     */
    private void showPermissionSettingsDialog(List<String> permanentlyDenied) {
        StringBuilder message = new StringBuilder();
        message.append("You have permanently denied the following permissions:\n\n");

        for (String permission : permanentlyDenied) {
            String simpleName = permission.replace("android.permission.", "");
            message.append("• ").append(simpleName).append("\n");
        }

        message.append("\nPlease enable them in Settings to use this app.");

        new AlertDialog.Builder(this)
                .setTitle("Enable Permissions in Settings")
                .setMessage(message.toString())
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Log.d(TAG, "Opening app settings");
                    openAppSettings();
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    Log.d(TAG, "User declined to open settings - exiting app");
                    finish();
                })
                .setCancelable(false)
                .show();
    }
    /**
     * Open app settings page
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);

        try {
            startActivity(intent);
            Toast.makeText(this, "Please enable all permissions", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Cannot open settings", e);
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
        }
    }

    // Simple onResume - just check and start if ready
    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "onResume called");

        // Check if initialization is complete
        if (!isInitialized) {
            if (hasAllRequiredPermissions()) {
                Log.d(TAG, "Permissions granted, completing initialization...");
                completeInitialization();
            } else {
                Log.d(TAG, "Requesting permissions...");
                checkAndRequestPermissions();
            }
        } else {
            Log.d(TAG, "Already initialized");

            // Re-register receivers if needed
            if (wifiDirectBroadcastReceiver != null && wifiDirectIntentFilter != null && !isWifiDirectReceiverRegistered) {
                try {
                    registerReceiver(wifiDirectBroadcastReceiver, wifiDirectIntentFilter);
                    isWifiDirectReceiverRegistered = true;
                    Log.d(TAG, "✓ Wi-Fi Direct receiver re-registered");
                } catch (Exception e) {
                    Log.e(TAG, "Error re-registering Wi-Fi receiver", e);
                }
            }

            if (bluetoothReceiver != null && bluetoothIntentFilter != null && !isBluetoothReceiverRegistered) {
                try {
                    registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
                    isBluetoothReceiverRegistered = true;
                    Log.d(TAG, "✓ Bluetooth receiver re-registered");
                } catch (Exception e) {
                    Log.e(TAG, "Error re-registering Bluetooth receiver", e);
                }
            }
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        int itemId = item.getItemId();

        // ════════════════════════════════════════════════════════════════════
        // ROUTING PROTOCOL SELECTION
        // ════════════════════════════════════════════════════════════════════

        if (itemId == R.id.menu_epidemic) {
            currentProtocol = "EPIDEMIC";
            updateProtocolDisplay();
            Toast.makeText(this, "✓ Switched to Epidemic Routing", Toast.LENGTH_SHORT).show();
            editor.putString(KEY_ROUTING_PROTOCOL, currentProtocol);
            editor.apply();
            return true;

        } else if (itemId == R.id.menu_spray_and_wait) {
            currentProtocol = "SPRAY_AND_WAIT";
            updateProtocolDisplay();
            Toast.makeText(this, "✓ Switched to Spray and Wait", Toast.LENGTH_SHORT).show();
            editor.putString(KEY_ROUTING_PROTOCOL, currentProtocol);
            editor.apply();
            return true;
        }

        // ════════════════════════════════════════════════════════════════════
        // TRANSPORT SELECTION: WI-FI DIRECT
        // ════════════════════════════════════════════════════════════════════

        if (itemId == R.id.menu_transport_wifi) {
            Log.d(TAG, "Switching to Wi-Fi Direct...");

            // Stop Bluetooth first
            stopBluetooth();

            // Switch to Wi-Fi Direct
            if (isWifiDirectAvailable()) {
                activeTransport = TransportType.WIFI_DIRECT;
                updateTransportDisplay();
                Toast.makeText(this, "✓ Switched to Wi-Fi Direct", Toast.LENGTH_SHORT).show();

                // Start discovery
                startWifiDirectDiscovery();

                // Save preference
                editor.putString(TRANSPORT_PREF, TransportType.WIFI_DIRECT.toString());
                editor.apply();
            } else {
                Toast.makeText(this, "✗ Wi-Fi Direct not available", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        // ════════════════════════════════════════════════════════════════════
        // TRANSPORT SELECTION: BLUETOOTH
        // ════════════════════════════════════════════════════════════════════

        if (itemId == R.id.menu_transport_bluetooth) {
            Log.d(TAG, "Switching to Bluetooth...");


            // Stop Wi-Fi Direct
            stopWifiDirect();
            initializeBluetooth();// sets up bluetooth
            makeBluetoothDiscoverable();

            // Switch transport
            activeTransport = TransportType.BLUETOOTH;
            updateTransportDisplay();
            Log.d(TAG, "=== BLUETOOTH SERVER DEBUG ===");
            Log.d(TAG, "bluetoothAdapter: " + (bluetoothAdapter != null ? "Available" : "NULL"));
            Log.d(TAG, "bluetoothAdapter.isEnabled(): " + (bluetoothAdapter != null ? bluetoothAdapter.isEnabled() : "N/A"));
            Log.d(TAG, "bluetoothServerThread: " + (bluetoothServerThread != null ? "Exists" : "NULL"));
            Log.d(TAG, "bluetoothServerThread.isAlive(): " + (bluetoothServerThread != null ? bluetoothServerThread.isAlive() : "N/A"));
            Log.d(TAG, "==============================");

           // Start Bluetooth server thread FIRST
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                if (bluetoothServerThread == null || !bluetoothServerThread.isAlive()) {
                    bluetoothServerThread = new BluetoothServerThread(bluetoothAdapter, handler);
                    bluetoothServerThread.start();
                    Log.d(TAG, "✓ Bluetooth server thread started (ready to accept connections)");
                } else {
                    Log.d(TAG, "Bluetooth server thread already running");
                }
            }else {
                Log.e(TAG, "❌ Cannot start server - Bluetooth adapter not available or disabled");
            }

            // Start discovery AFTER server is running
            startBluetoothDiscovery();
            Toast.makeText(this, "✓ Switched to Bluetooth", Toast.LENGTH_SHORT).show();

            // Save preference
            editor.putString(TRANSPORT_PREF, TransportType.BLUETOOTH.toString());
            editor.apply();

            return true;
        }

        // ════════════════════════════════════════════════════════════════════
        // VIEW FRIENDS
        // ═══════════════════════════════════════════════════════════════════

        if (itemId == R.id.menu_view_friends) {
            showFriendsDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void stopWifiDirect() {
        Log.d(TAG, "Stopping Wi-Fi Direct...");

        // Remove discovery callback
        if (discoveryHandler != null && discoveryRunnable != null) {
            discoveryHandler.removeCallbacks(discoveryRunnable);
        }

        // Cancel discovery
        if (manager != null && channel != null) {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Wi-Fi Direct discovery stopped");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Failed to stop discovery");
                }
            });
        }

        // Close threads
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.close();
            serverThread = null;
        }

        if (clientThread != null && clientThread.isAlive()) {
            clientThread.close();
            clientThread = null;
        }

        statusTextView.setText("Status: Switched to Bluetooth");
        statusTextView.setTextColor(0xFF2196F3);
    }

    private void startWifiDirectDiscovery() {
        Log.d(TAG, "Starting Wi-Fi Direct discovery...");

        if (manager == null || channel == null) {
            Log.e(TAG, "Manager or channel is null");
            return;
        }

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "✓ Wi-Fi Direct discovery started");
                statusTextView.setText("Status: Discovering (Wi-Fi Direct)...");
                statusTextView.setTextColor(0xFFFFC107);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Wi-Fi Direct discovery failed: " + reason);
                statusTextView.setText("Status: Discovery Failed");
            }
        });
    }

    private void stopBluetooth() {
        Log.d(TAG, "Stopping Bluetooth...");

        isBluetoothConnected = false;
        connectedBluetoothDeviceName = null;

        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            try {
                bluetoothAdapter.cancelDiscovery();
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error canceling discovery", e);
            }
        }

        // ✅ MESH FIX: Close all client threads
        for (BluetoothClientThread client : bluetoothClientThreads) {
            if (client != null && client.isAlive()) {
                client.close();
            }
        }
        bluetoothClientThreads.clear();

        if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
            bluetoothServerThread.close();
            bluetoothServerThread = null;
        }

        connectedDeviceAddresses.clear();
        connectionAttempts.clear();

        statusTextView.setText("Status: Switched to Wi-Fi Direct");
        statusTextView.setTextColor(0xFF4CAF50);

        Log.d(TAG, "✓ Bluetooth stopped, all connections closed");
    }

    private void showFriendsDialog() {
        executor.execute(() -> {
            List<Friend> friends = friendDao.getAllFriends();

            runOnUiThread(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                View dialogView = getLayoutInflater().inflate(android.R.layout.simple_list_item_1, null);

                // Create main layout
                LinearLayout mainLayout = new LinearLayout(this);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setPadding(50, 40, 50, 40);

                // Title
                TextView title = new TextView(this);
                title.setText("Send Message");
                title.setTextSize(20);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setPadding(0, 0, 0, 20);
                mainLayout.addView(title);

                // Friends list section
                TextView friendsLabel = new TextView(this);
                friendsLabel.setText("Select Friend:");
                friendsLabel.setTextSize(16);
                friendsLabel.setPadding(0, 10, 0, 10);
                mainLayout.addView(friendsLabel);

                // ListView for friends
                ListView friendsListView = new ListView(this);
                List<String> friendDisplayNames = new ArrayList<>();
                for (Friend friend : friends) {
                    friendDisplayNames.add(friend.friendlyName);
                }
                ArrayAdapter<String> friendsAdapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_list_item_1,
                        friendDisplayNames
                );
                friendsListView.setAdapter(friendsAdapter);

                // Set height for ListView
                LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        400 // Fixed height in pixels
                );
                friendsListView.setLayoutParams(listParams);
                mainLayout.addView(friendsListView);

                // OR separator
                TextView orLabel = new TextView(this);
                orLabel.setText("OR");
                orLabel.setTextSize(14);
                orLabel.setGravity(android.view.Gravity.CENTER);
                orLabel.setPadding(0, 20, 0, 10);
                mainLayout.addView(orLabel);

                // Add new friend section
                TextView addFriendLabel = new TextView(this);
                addFriendLabel.setText("Add New Friend:");
                addFriendLabel.setTextSize(16);
                addFriendLabel.setPadding(0, 10, 0, 10);
                mainLayout.addView(addFriendLabel);

                // Device name input
                EditText deviceNameInput = new EditText(this);
                deviceNameInput.setHint("Device name (e.g., Redmi Note 12)");
                deviceNameInput.setSingleLine(true);
                mainLayout.addView(deviceNameInput);

                // Friendly name input
                EditText friendlyNameInput = new EditText(this);
                friendlyNameInput.setHint("Friendly name (e.g., Alice)");
                friendlyNameInput.setSingleLine(true);
                mainLayout.addView(friendlyNameInput);

                // Message input
                TextView messageLabel = new TextView(this);
                messageLabel.setText("Message:");
                messageLabel.setTextSize(16);
                messageLabel.setPadding(0, 20, 0, 10);
                mainLayout.addView(messageLabel);

                EditText messageInput = new EditText(this);
                messageInput.setHint("Type your message here");
                messageInput.setLines(3);
                mainLayout.addView(messageInput);

                // Track selected friend
                final int[] selectedFriendIndex = {-1};

                // Handle friend selection
                friendsListView.setOnItemClickListener((parent, view, position, id) -> {
                    selectedFriendIndex[0] = position;
                    // Clear manual input when friend is selected
                    deviceNameInput.setText("");
                    friendlyNameInput.setText("");

                    // Highlight selected item
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        parent.getChildAt(i).setBackgroundColor(
                                i == position ? 0xFF6200EE : 0x00000000
                        );
                    }

                    Toast.makeText(this,
                            "Selected: " + friends.get(position).friendlyName,
                            Toast.LENGTH_SHORT).show();
                });

                builder.setView(mainLayout);

                builder.setPositiveButton("Send", (dialog, which) -> {
                    String messageText = messageInput.getText().toString().trim();

                    if (messageText.isEmpty()) {
                        Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Option 1: Manual device name - Add to friends first, then send
                    String manualDeviceName = deviceNameInput.getText().toString().trim();
                    String manualFriendlyName = friendlyNameInput.getText().toString().trim();

                    if (!manualDeviceName.isEmpty()) {
                        // Use device name if no friendly name provided
                        if (manualFriendlyName.isEmpty()) {
                            manualFriendlyName = manualDeviceName;
                        }

                        final String finalFriendlyName = manualFriendlyName;

                        // Add to friends list if not exists
                        executor.execute(() -> {
                            Friend existingFriend = friendDao.getFriendById(manualDeviceName);

                            if (existingFriend == null) {
                                // Create new friend
                                Friend newFriend = new Friend();
                                newFriend.deviceId = manualDeviceName;
                                newFriend.friendlyName = finalFriendlyName;
                                newFriend.lastEncounteredTimestamp = System.currentTimeMillis();

                                friendDao.insert(newFriend);

                                runOnUiThread(() -> {
                                    Toast.makeText(this,
                                            "Added " + finalFriendlyName + " to friends",
                                            Toast.LENGTH_SHORT).show();
                                });

                                Log.d(TAG, "Added new friend: " + finalFriendlyName);
                            }

                            // Send message to this friend
                            Friend targetFriend = new Friend();
                            targetFriend.deviceId = manualDeviceName;
                            targetFriend.friendlyName = finalFriendlyName;

                            sendMessageToFriend(targetFriend, messageText);
                        });

                    } else if (selectedFriendIndex[0] >= 0) {
                        // Option 2: Friend selected from list
                        Friend selectedFriend = friends.get(selectedFriendIndex[0]);
                        sendMessageToFriend(selectedFriend, messageText);

                    } else {
                        Toast.makeText(this,
                                "Please select a friend or enter device name",
                                Toast.LENGTH_SHORT).show();
                    }
                });

                builder.setNegativeButton("Cancel", null);

                AlertDialog dialog = builder.create();
                dialog.show();
            });
        });
    }

    private void sendMessageToFriend(Friend friend, String messageText) {
        executor.execute(() -> {
            try {
                // Create new message
                Message message = new Message();
                message.message_id = UUID.randomUUID().toString();

                // Encrypt the message
                message.encrypted_payload = CryptoUtils.encrypt(messageText);
                message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);

                // Set routing information
                message.source_id = getMyDeviceName(); // Your device Name
                message.destination_id = friend.deviceId;

                // Set message propertiesg
                message.message_type = Message.TYPE_DATA;
                message.priority = 1; // High priority
                message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2 hours
                message.hop_count = 0;
                message.is_delivered = false;

                // Set copy count based on routing protocol
                if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                    message.copy_count = 6; // Spray-and-Wait uses multiple copies
                } else {
                    message.copy_count = 1; // Epidemic routing
                }

                // Save message to database
                messageDao.insert(message);

                Log.d(TAG, "Message saved for friend: " + friend.friendlyName + " (ID: " + friend.deviceId + ")");

                // Update UI and send via network
                runOnUiThread(() -> {
                    // Display in chat
                    String displayText = String.format("Me → %s: %s",
                            friend.friendlyName, messageText);
                    ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, false);
                    chatMessages.add(chatMessage);
                    chatAdapter.notifyDataSetChanged();

                    // Scroll to bottom
                    chatListView.smoothScrollToPosition(chatMessages.size() - 1);

                    // Show confirmation
                    Toast.makeText(this,
                            "Message queued for " + friend.friendlyName,
                            Toast.LENGTH_SHORT).show();

                    // Try to send immediately if connected
                    sendViaNetwork(message);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending message to friend", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to send message: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    //  to broadcast message to all neighbors
    private void sendViaNetwork(Message message) {
        int devicesSentTo = 0;
        Set<String> sentToAddresses = new HashSet<>();

        if (activeTransport == TransportType.BLUETOOTH) {
            Log.d(TAG, "📡 Broadcasting via Bluetooth mesh...");

            // ✅ MESH FIX: Send via server (incoming connections)
            if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                try {
                    bluetoothServerThread.broadcastToAll(message);
                    devicesSentTo += bluetoothServerThread.getConnectedClientCount();
                    Log.d(TAG, "✓ Broadcasted via Bluetooth ServerThread to " +
                            bluetoothServerThread.getConnectedClientCount() + " clients");
                } catch (Exception e) {
                    Log.e(TAG, "Error broadcasting via server", e);
                }
            }

            // ✅ MESH FIX: Send via ALL client connections (outgoing connections)
            for (BluetoothClientThread client : bluetoothClientThreads) {
                if (client.isConnected()) {
                    try {
                        String remoteAddress = client.getRemoteDeviceAddress();

                        // Avoid sending duplicate to same device
                        if (!sentToAddresses.contains(remoteAddress)) {
                            client.write(message);
                            sentToAddresses.add(remoteAddress);
                            devicesSentTo++;
                            Log.d(TAG, "✓ Sent via Bluetooth ClientThread to " + remoteAddress);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending via client", e);
                    }
                }
            }

            if (devicesSentTo == 0) {
                Log.w(TAG, "⚠️ No active Bluetooth connections");
            } else {
                Log.d(TAG, "✅ Broadcast to " + devicesSentTo + " Bluetooth connections");
            }

        } else if (activeTransport == TransportType.WIFI_DIRECT) {
            // Wi-Fi Direct transmission
            Log.d(TAG, "Attempting Wi-Fi Direct transmission...");

            if (serverThread != null && serverThread.isConnected()) {
                serverThread.write(message);
                devicesSentTo++;
                Log.d(TAG, "✓ Sent via Wi-Fi Direct ServerThread");
            }

            if (clientThread != null && clientThread.isConnected()) {
                clientThread.write(message);
                devicesSentTo++;
                Log.d(TAG, "✓ Sent via Wi-Fi Direct ClientThread");
            }

            if (devicesSentTo == 0) {
                Log.e(TAG, "Wi-Fi Direct threads not available");
            }
        }

        final int finalDevicesSentTo = devicesSentTo;

        if (devicesSentTo > 0) {
            Log.d(TAG, "✅✅✅ Message transmitted successfully!");
            Log.d(TAG, "  → Message ID: " + message.message_id);
            Log.d(TAG, "  → Sent to " + devicesSentTo + " device(s)");
            Log.d(TAG, "  → Destination: " + message.destination_id);
            Log.d(TAG, "  → Protocol: " + currentProtocol);
            Log.d(TAG, "  → Transport: " + activeTransport);

            runOnUiThread(() -> {
                String successMsg = finalDevicesSentTo > 1
                        ? "✓ Broadcasted to " + finalDevicesSentTo + " devices"
                        : "✓ Message sent";
                Toast.makeText(MainActivity.this, successMsg, Toast.LENGTH_SHORT).show();
            });

        } else {
            Log.e(TAG, "✗ Failed to transmit message - no active connections");
            Log.e(TAG, "  → Message stored in database for later forwarding");
            Log.e(TAG, "  → Message ID: " + message.message_id);

            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "No active connection - message queued for forwarding",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    public void updateBluetoothDeviceList(List<BluetoothDevice> devices) {
        if (devices == null) {
            Log.e(TAG, "updateBluetoothDeviceList called with NULL devices");
            return;
        }

        if (!devices.isEmpty()) {
            // Only update if we have devices
            discoveredDevices.clear();
            discoveredDevices.addAll(devices);
            Log.d(TAG, "✓ Updated discoveredDevices list (size: " + discoveredDevices.size() + ")");
        } else {
            Log.d(TAG, "⚠️ Empty device list received - keeping existing " + discoveredDevices.size() + " devices");
            // Use existing discoveredDevices list instead
            devices = new ArrayList<>(discoveredDevices);
        }

        Log.d(TAG, "updateBluetoothDeviceList called with " + devices.size() + " devices");
        final List<BluetoothDevice> finalDevices = new ArrayList<>(discoveredDevices);

        runOnUiThread(() -> {
            List<String> deviceNames = new ArrayList<>();

            for (int i = 0; i < finalDevices.size(); i++) {
                BluetoothDevice device = finalDevices.get(i);

                // Check if DTN device
                if (!isDTNDevice(device)) {
                    Log.d(TAG, "  ✗ Skipped non-DTN device at index " + i);
                    continue;
                }

                String name = "Unknown";
                String address = device.getAddress();

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED) {
                            name = device.getName();
                        }
                    } else {
                        name = device.getName();
                    }

                    if (name == null || name.trim().isEmpty()) {
                        name = "Unknown Device";
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Permission error getting device name", e);
                }

                // ✅ Show connection status
                String displayName;
                if (connectedDeviceAddresses.contains(address)) {
                    displayName = name + " ✓ (Connected)";
                } else {
                    displayName = name;
                }

                deviceNames.add((displayName));
                Log.d(TAG, "  ✓ Added device: " + displayName + " @ " + address);
            }

            if (deviceNames.isEmpty()) {
                deviceNames.add("No DTN devices found");
                Log.d(TAG, "No DTN devices to display");
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    MainActivity.this,
                    android.R.layout.simple_list_item_1,
                    deviceNames
            );

            peerListView.setAdapter(adapter);
            Log.d(TAG, "✓ Peer list updated: " + deviceNames.size() + " items");

            if (!finalDevices.isEmpty()) {
                statusTextView.setText("Status: Found " + finalDevices.size() + " device(s)");
                statusTextView.setTextColor(0xFF4CAF50); // Green
            }
        });
    }

    public boolean isDTNDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }

        try {
            // Get device type
            int deviceType = device.getType();

            // Accept Classic and Dual mode devices (most Android phones)
            if (deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                    deviceType == BluetoothDevice.DEVICE_TYPE_DUAL) {

                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot get device name for DTN check");
                }

                // If has reasonable name, likely a phone
                if (name != null && !name.isEmpty() && name.length() < 50) {
                    // Check it's not an audio device
                    int deviceClass = device.getBluetoothClass().getDeviceClass();
                    boolean isAudioDevice = (deviceClass & 0x400) != 0;

                    if (!isAudioDevice) {
                        Log.d(TAG, "  ✓ DTN device: " + name);
                        return true;
                    }
                }
            }

            //  accept LE devices that look like phones
            if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    // Ignore
                }

                if (name != null && !name.isEmpty()) {
                    Log.d(TAG, "  ✓ Potential DTN device (BLE): " + name);
                    return true; // Accept BLE devices with names
                }
            }

            Log.d(TAG, "  ✗ Not DTN device (type: " + deviceType + ")");
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking device: " + e.getMessage());
            return false;
        }
    }
    public void onBluetoothDiscoveryFinished(List<BluetoothDevice> devices) {
        if(devices == null){
            devices = new ArrayList<>();
        }

        Log.d(TAG, "onBluetoothDiscoveryFinished: Found " + devices.size() + " devices");
        discoveredDevices.clear();
        discoveredDevices.addAll(devices);

        runOnUiThread(() -> {
            statusTextView.setText("Status: Found " + discoveredDevices.size() + " devices");

            if (discoveredDevices.size() > 0) {
                Toast.makeText(MainActivity.this,
                        "✓ Found " + discoveredDevices.size() + " device(s)",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this,
                        "No devices found",
                        Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                        startBluetoothDiscovery();
                    }
                }, 5000);
            }
        });
    }

    private ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Log.d(TAG, "✓ Bluetooth enabled by user");

                    startBluetoothServerThread();
                    startBluetoothDiscovery();

                } else {
                    Log.w(TAG, "User declined to enable Bluetooth");
                    Toast.makeText(this, "Bluetooth required for mesh networking", Toast.LENGTH_LONG).show();
                }
            });
    @SuppressLint("MissingPermission")
    private void makeBluetoothDiscoverable() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter is null");
            return;
        }

        if (isDiscoverableDialogShown) {
            Log.d(TAG, "Discoverable dialog already shown, skipping");
            return;
        }


        // Permission check for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ BLUETOOTH_ADVERTISE permission not granted");
                return;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ BLUETOOTH_SCAN permission not granted");
                return;
            }
        }

        // ✅ CHECK IF ALREADY DISCOVERABLE
        int scanMode = bluetoothAdapter.getScanMode();

        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Log.d(TAG, "✅ Device is ALREADY discoverable - skipping dialog");
            Toast.makeText(this, "Device is already discoverable", Toast.LENGTH_SHORT).show();
            isDiscoverableDialogShown = true ;
            return; // DON'T show dialog again!
        }

        Log.d(TAG, "📡 Requesting discoverability (current scan mode: " + scanMode + ")");

        // Request discoverable mode
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300); // 5 minutes

        try {
            discoverableLauncher.launch(discoverableIntent);
            isDiscoverableDialogShown = true;
            Log.d(TAG, "✅ Discoverable intent launched");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error launching discoverable intent", e);
            Toast.makeText(this, "Error making device discoverable", Toast.LENGTH_SHORT).show();
        }
    }
    private ActivityResultLauncher<Intent> discoverableLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == 300) { // Duration we requested
                    Log.d(TAG, "✅ Device is now discoverable for 5 minutes");
                    Toast.makeText(this, "Device is now discoverable", Toast.LENGTH_SHORT).show();
                } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                    Log.w(TAG, "⚠️ User declined to make device discoverable");
                    Toast.makeText(this, "Discoverability denied - mesh mode limited", Toast.LENGTH_LONG).show();
                }
            }
    );
    private void startAutomaticMeshMode() {
        Log.d(TAG, "Starting automatic mesh mode...");

        // Make device discoverable
        makeBluetoothDiscoverable();

        // Start continuous discovery
        startContinuousDiscovery();

        // Auto-connect to all nearby devices
        enableAutoConnect = true;
    }
    private void startContinuousDiscovery() {
        // Use a periodic task to scan for devices
        discoveryRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Auto-discovery scan...");

                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    // Start Bluetooth discovery
                    startBluetoothDiscovery();
                }

                if (manager != null) {
                    // Start WiFi Direct discovery
                    startWifiDirectDiscovery();
                }

                // Repeat every 60n j.00 seconds
                discoveryHandler.postDelayed(this, 60000);
            }
        };

        // Start first scan immediately
        discoveryHandler.post(discoveryRunnable);
        Log.d(TAG, "✓ Continuous discovery started");
    }

    public void autoConnectToFriends(List<BluetoothDevice> discoveredDevices) {
        if (!enableAutoConnect) {
            Log.d(TAG, "Auto-connect disabled");
            return;
        }

        // Clean up dead connections first
        cleanupDeadConnections();

        int currentConnections = getTotalActiveConnections();

        // Try to maintain optimal mesh density
        if (currentConnections >= MIN_MESH_CONNECTIONS && currentConnections < MAX_CLIENT_CONNECTIONS) {
            Log.d(TAG, "Mesh density acceptable: " + currentConnections + " connections");
            // Continue connecting to build denser mesh
        }

        executor.execute(() -> {
            // Get friends from database
            List<Friend> friends = friendDao.getAllFriends();
            Set<String> friendAddresses = new HashSet<>();

            for (Friend friend : friends) {
                friendAddresses.add(friend.deviceId);
            }

            Log.d(TAG, "Auto-connecting: " + friendAddresses.size() + " friends in database");

            // Connect to multiple friends, not just first
            int connectionsAttempted = 0;

            for (BluetoothDevice device : discoveredDevices) {
                String deviceAddress = device.getAddress();

                // Check if it's a friend
                if (!friendAddresses.contains(deviceAddress)) {
                    continue;
                }

                // Check if already connected
                if (isAlreadyConnectedTo(deviceAddress)) {
                    Log.d(TAG, "Already connected to friend: " + device.getName());
                    continue;
                }

                // Check connection limit
                if (bluetoothClientThreads.size() >= MAX_CLIENT_CONNECTIONS) {
                    Log.d(TAG, "Max connections reached, stopping auto-connect");
                    break;
                }

                String deviceName = device.getName() != null ? device.getName() : "Unknown";
                Log.d(TAG, "Auto-connecting to friend: " + deviceName);

                runOnUiThread(() -> {
                    connectToBluetoothDevice(device);
                });

                connectionsAttempted++;

                // Add delay between connection attempts
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.d(TAG, "Auto-connect completed: " + connectionsAttempted + " attempts");
        });
    }

    /**
     * Update connection status display
     */
    private void updateConnectionStatus() {
        int totalConnections = bluetoothClientThreads.size();

        if (bluetoothServerThread != null) {
            totalConnections += bluetoothServerThread.getConnectedClientCount();
        }

        if (totalConnections == 0) {
            statusTextView.setText("Status: Disconnected");
            statusTextView.setTextColor(0xFFF44336); // Red
            isBluetoothConnected = false;
        } else if (totalConnections == 1) {
            statusTextView.setText("Status: Connected (1 peer)");
            statusTextView.setTextColor(0xFFFFC107); // Yellow
            isBluetoothConnected = true;
        } else {
            statusTextView.setText("Status: Mesh Active (" + totalConnections + " peers)");
            statusTextView.setTextColor(0xFF4CAF50); // Green
            isBluetoothConnected = true;
        }

        Log.d(TAG, "Connection status: " + totalConnections + " active connections");
    }

    /**
     * Thread-safe method to check if already connected
     */
    private synchronized boolean isAlreadyConnectedTo(String deviceAddress) {
        if (deviceAddress == null) {
            return false;
        }

        for (BluetoothClientThread thread : bluetoothClientThreads) {
            if (thread != null &&
                    deviceAddress.equals(thread.getRemoteDeviceAddress()) &&
                    thread.isConnected()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Thread-safe method to get active connection count
     */
    private synchronized int getTotalActiveConnections() {
        int count = 0;

        // Count active client connections
        for (BluetoothClientThread thread : new ArrayList<>(bluetoothClientThreads)) {
            if (thread != null && thread.isConnected()) {
                count++;
            }
        }

        // Add server connections
        if (bluetoothServerThread != null) {
            count += bluetoothServerThread.getConnectedClientCount();
        }

        return count;
    }

    /**
     * Thread-safe method to clean up dead connections
     */
    private synchronized void cleanupDeadConnections() {
        int before = bluetoothClientThreads.size();

        bluetoothClientThreads.removeIf(thread -> {
            if (thread == null) {
                return true;
            }

            if (!thread.isAlive() || !thread.isConnected()) {
                String address = thread.getRemoteDeviceAddress();
                connectedDeviceAddresses.remove(address);
                Log.d(TAG, "Cleaned up dead connection: " + address);
                return true;
            }

            return false;
        });

        int removed = before - bluetoothClientThreads.size();
        if (removed > 0) {
            Log.d(TAG, "Cleaned up " + removed + " dead connections. Active: " + bluetoothClientThreads.size());
        }
    }

    private boolean hasBluetoothPermission(String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, permission)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Not required on older versions
    }

    /**
     * Safe Bluetooth device name getter
     */
    private String getBluetoothDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "Unknown";
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    String name = device.getName();
                    return (name != null && !name.isEmpty()) ? name : "Device_" + device.getAddress();
                }
            } else {
                String name = device.getName();
                return (name != null && !name.isEmpty()) ? name : "Device_" + device.getAddress();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error getting device name", e);
        }

        return "Device_" + device.getAddress().replace(":", "");
    }

    /**
     * Safe Bluetooth adapter name getter
     */
    private String getBluetoothAdapterName() {
        if (bluetoothAdapter == null) {
            return null;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    return bluetoothAdapter.getName();
                }
            } else {
                return bluetoothAdapter.getName();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error getting adapter name", e);
        }

        return null;
    }
    /**
     * Thread-safe method to add Bluetooth client
     */
    private synchronized void addBluetoothClient(BluetoothClientThread client) {
        if (client == null) {
            return;
        }

        String address = client.getRemoteDeviceAddress();

        // Remove any existing connection to this address
        bluetoothClientThreads.removeIf(thread ->
                address.equals(thread.getRemoteDeviceAddress())
        );

        // Add new connection
        bluetoothClientThreads.add(client);
        connectedDeviceAddresses.add(address);

        Log.d(TAG, "Added BT client: " + address + " (Total: " + bluetoothClientThreads.size() + ")");
    }
    /**
     * Thread-safe method to remove Bluetooth client
     */
    private synchronized void removeBluetoothClient(String deviceAddress) {
        if (deviceAddress == null) {
            return;
        }

        boolean removed = bluetoothClientThreads.removeIf(thread ->
                deviceAddress.equals(thread.getRemoteDeviceAddress())
        );

        if (removed) {
            connectedDeviceAddresses.remove(deviceAddress);
            Log.d(TAG, "Removed BT client: " + deviceAddress + " (Remaining: " + bluetoothClientThreads.size() + ")");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (discoveryHandler != null && discoveryRunnable != null) {
            discoveryHandler.removeCallbacks(discoveryRunnable);
        }

        // Shutdown threads
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.close();
        }
        if (clientThread != null && clientThread.isAlive()) {
            clientThread.close();
        }

        // ✅ MESH FIX: Close all Bluetooth client threads
        for (BluetoothClientThread client : bluetoothClientThreads) {
            if (client != null && client.isAlive()) {
                client.close();
            }
        }
        bluetoothClientThreads.clear();

        if (bluetoothServerThread != null) {
            bluetoothServerThread.close();
        }

        // Shutdown routing executors
        if (epidemicRouting != null) {
            epidemicRouting.shutdown();
        }
        if (sprayAndWaitRouting != null) {
            sprayAndWaitRouting.shutdown();
        }

        // Shutdown logger
        Logger loggerInstance = Logger.getInstance(this);
        loggerInstance.shutdown();

        // Close database
        AppDatabase.closeDatabase();

        // Unregister receivers
        try {
            if (isWifiDirectReceiverRegistered && wifiDirectBroadcastReceiver != null) {
                unregisterReceiver(wifiDirectBroadcastReceiver);
                isWifiDirectReceiverRegistered = false;
            }
            if (isBluetoothReceiverRegistered && bluetoothReceiver != null) {
                unregisterReceiver(bluetoothReceiver);
                isBluetoothReceiverRegistered = false;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error unregistering receivers", e);
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }

        Log.d(TAG, "MainActivity destroyed - cleanup complete");
    }


}
