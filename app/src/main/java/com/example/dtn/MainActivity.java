package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.text.InputType;
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
import androidx.annotation.RequiresApi;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private List<BluetoothDevice> discoveredDevices = new ArrayList<>();

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
    private List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);  // This is the FIRST method called when MainActivity launches
        setContentView(R.layout.activity_main); // Loads the XML layout file (activity_main.xml) into memory

        // Load saved protocol preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); // Gets the saved preferences file named "DTNPrefs"
        currentProtocol = prefs.getString(KEY_ROUTING_PROTOCOL, "EPIDEMIC");
        String savedTransport = prefs.getString(TRANSPORT_PREF, "WIFI_DIRECT");
        activeTransport = TransportType.valueOf(savedTransport); // Convert string to ENUM type


        requestPermissions(); // Asks user for runtime permissions (Android 6+)
        initializeUI(); // Calls findViewById() to link Java variables to XML layout elements
        updateProtocolDisplay(); // Updates the UI to show current protocol
        updateTransportDisplay(); // Updates the UI to show current transport
        setupPrioritySpinner(); // Initializes the priority dropdown menu


        initializeDatabase(); // Creates/opens Room database
        logger = Logger.getInstance(getApplicationContext()); // All threads can log to same file
        initializeHandler(); // Creates a Handler for message passing between threads
        setListeners(); // Attaches click listeners to UI buttons


        // Initialize both transports
        initializeWifiDirect(); // Sets up Wi-Fi Direct
//        initializeBluetooth();// sets up bluetooth
//        makeBluetoothDiscoverable();
        


        // Chooses which transport to use (Wi-Fi Direct or Bluetooth)
        selectBestTransport();
        Log.d(TAG, "onCreate() completed. Initial ownDeviceId: " + ownDeviceId);

        startAutomaticMeshMode();


    }
    //  REQUEST ALL THE PERMISSIONS REQUIRED
    private void requestPermissions() {
        Log.d(TAG, "Checking and requesting permissions...");

        List<String> permissionsToRequest = new ArrayList<>();

        // ════════════════════════════════════════════════════════════════════
        // STEP 1: Location (needed for both Wi-Fi Direct and Bluetooth)
        // ════════════════════════════════════════════════════════════════════

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            Log.d(TAG, "Need ACCESS_FINE_LOCATION");
        }

        // ════════════════════════════════════════════════════════════════════
        // STEP 2: Bluetooth Permissions (Android 12+)
        // ════════════════════════════════════════════════════════════════════

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
                Log.d(TAG, "Need BLUETOOTH_SCAN");
            }

            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
                Log.d(TAG, "Need BLUETOOTH_CONNECT");
            }

            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
                Log.d(TAG, "Need BLUETOOTH_ADVERTISE");
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STEP 3: Wi-Fi Direct (Android 13+)
        // ════════════════════════════════════════════════════════════════════

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
                Log.d(TAG, "Need NEARBY_WIFI_DEVICES");
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STEP 4: External Storage (Android 12 and below)
        // ════════════════════════════════════════════════════════════════════

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                Log.d(TAG, "Need WRITE_EXTERNAL_STORAGE");
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STEP 5: Request all missing permissions
        // ════════════════════════════════════════════════════════════════════

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "💬 Requesting " + permissionsToRequest.size() + " permissions");
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
        } else {
            Log.d(TAG, "✓ All permissions already granted");
            // All permissions already granted, start discovery
            discoverPeers();
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
                    handleReceivedMessage(receivedMessage);  // Same handler!
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
                String deviceName = (String) msg.obj;

                Log.d(TAG, "✓ Handler: Connection established with " + deviceName);

                isBluetoothConnected = true;
                connectedBluetoothDeviceName = deviceName;

                runOnUiThread(() -> {
                    statusTextView.setText("Status: ✓ Connected to " + deviceName);
                    statusTextView.setTextColor(0xFF4CAF50); // Green
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

                // Clear connection flags
                isBluetoothConnected = false;
                connectedBluetoothDeviceName = null;
                connectedDeviceAddresses.clear();

                // Stop threads
                if (bluetoothClientThread != null) {
                    bluetoothClientThread.close();
                    bluetoothClientThread = null;
                }

                if (bluetoothServerThread != null) {
                    Log.d(TAG, "Server thread kept alive for new connections");
                }

                // Update UI
                runOnUiThread(() -> {
                    statusTextView.setText("Status: Connection lost");
                    statusTextView.setTextColor(0xFFF44336);  // Red
                    Toast.makeText(MainActivity.this,
                            "⚠ Connection lost!",
                            Toast.LENGTH_SHORT).show();
                });

                Log.d(TAG, "✓ Cleaned up after connection loss");
                return true;
            }
            return false;
        });
    }

    private void setListeners() {
        peerListView.setOnItemLongClickListener((parent, view, position, id) -> { // Add friend
            if (activeTransport == TransportType.WIFI_DIRECT) {
                if (deviceArray == null || position >= deviceArray.length) return false;
                final WifiP2pDevice device = deviceArray[position];
                new AlertDialog.Builder(this)
                        .setTitle("Add Friend")
                        .setMessage("Add " + device.deviceName + " as friend?")
                        .setPositiveButton("Yes", (dialog, which) -> addFriend(device.deviceAddress , device.deviceName))
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
                        .setMessage("Add " + device.getName() + " as friend?")
                        .setPositiveButton("Yes", (dialog, which) -> addFriend(device.getAddress() , device.getName()))
                        .setNegativeButton("No", null)
                        .show();
            }
            return true;
        });

        peerListView.setOnItemClickListener((parent, view, position, id) -> { //  Connect to selected peer
            if (activeTransport == TransportType.WIFI_DIRECT) {
                connectToWifiDirectDevice(position);
            } else {
                if (discoveredDevices == null || position >= discoveredDevices.size()) {
                    Toast.makeText(this, "Invalid device selection", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Invalid position: " + position + ", list size: " +
                            (discoveredDevices != null ? discoveredDevices.size() : 0));
                    return;
                }

                // ✓ Get device from list and connect
                BluetoothDevice device = discoveredDevices.get(position);
                String deviceName = device.getName() != null ? device.getName() : "Unknown";


                Log.d(TAG, "User clicked to connect to: " + deviceName);

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
            // Bluetooth mode
            Log.d(TAG, "Sending via Bluetooth");

            // Check Bluetooth connection
            if (!isBluetoothConnected) {
                Toast.makeText(this, "Not connected via Bluetooth", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Cannot send: isBluetoothConnected = " + isBluetoothConnected);
                return;
            }

            // Check threads exist
            if (bluetoothClientThread == null && bluetoothServerThread == null) {
                Toast.makeText(this, "Bluetooth threads not initialized", Toast.LENGTH_SHORT).show();
                return;
            }

            //  Get target device name from Bluetooth connection
            // For Bluetooth, we know who we're connected to
            targetDevice = connectedBluetoothDeviceName;

            if (targetDevice == null || targetDevice.isEmpty()) {
                Toast.makeText(this, "Target device unknown", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (targetDevice == null) {
            Toast.makeText(this, "No valid target device", Toast.LENGTH_SHORT).show();
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
                message.source_id = ownDeviceId;
                message.destination_id = destinationId;
                message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH Priority") ? 1 : 0;
                message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000);
                message.hop_count = 0;
                message.copy_count = currentProtocol.equals("SPRAY_AND_WAIT") ? 6 : 1;

                messageDao.insert(message);
                Log.d(TAG, "Message saved: " + message.message_id);

                boolean sent = false;

                if (activeTransport == TransportType.BLUETOOTH) {
                    // ✓ Bluetooth transmission
                    Log.d(TAG, "Attempting Bluetooth transmission...");

                    if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
                        bluetoothClientThread.write(message);
                        sent = true;
                        Log.d(TAG, "✓ Sent via Bluetooth ClientThread");

                    } else if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                        bluetoothServerThread.write(message);
                        sent = true;
                        Log.d(TAG, "✓ Sent via Bluetooth ServerThread");

                    } else {
                        Log.e(TAG, "Bluetooth threads not available");
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

                if (sent) {
                    Log.d(TAG, "✓ Message transmitted: " + message.message_id);

                    // Update UI on main thread
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "✓ Message sent", Toast.LENGTH_SHORT).show();
                    });

                } else {
                    Log.e(TAG, "Failed to transmit message - no active connection");

                    // Update UI on main thread
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Failed to send - connection lost", Toast.LENGTH_SHORT).show();
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                e.printStackTrace();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

        // Refresh device ID immediately and synchronously
//        if (info.groupFormed) {
//            refreshDeviceIdAfterConnection();
//            Log.d(TAG, "Device ID after refresh: " + ownDeviceId);
//        }
        if (info.groupFormed) {
            Log.d(TAG, "Connection established - updating device ID...");
            Log.d(TAG, "BEFORE: ownDeviceId = " + ownDeviceId);

            // SYNCHRONOUS WAIT for device ID
            final Object lock = new Object();
            final String[] resultHolder = new String[1];

            if (manager != null && channel != null) {
                try {
                    manager.requestDeviceInfo(channel, device -> {
                        synchronized (lock) {
                            if (device != null && device.deviceAddress != null) {
                                resultHolder[0] = device.deviceAddress;
                                Log.d(TAG, "Got device info callback: " + resultHolder[0]);
                            }
                            lock.notifyAll();
                        }
                    });

                    // WAIT for callback
                    synchronized (lock) {
                        try {
                            lock.wait(10000);  // Max 10 seconds
                            if (resultHolder[0] != null) {
                                ownDeviceId = resultHolder[0];
                                Log.d(TAG, "✅ Device ID UPDATED: " + ownDeviceId);
                                // Save for next app launch
                                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                prefs.edit().putString("SAVED_DEVICE_ID", ownDeviceId).apply();
                            } else {
                                Log.w(TAG, "Callback timeout or returned null");
                            }
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted waiting for device ID");
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting device ID", e);
                }
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
                Friend existingFriend = friendDao.getFriendById(deviceId);
                if (existingFriend == null) {
                    Friend newFriend = new Friend();
                    newFriend.deviceId = deviceId;
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
                String peerAddress = null;
                String peerName;
                boolean isBluetoothPeer = false;

                if (peer instanceof WifiP2pDevice) {
                    // Wi-Fi Direct
                    WifiP2pDevice wifiPeer = (WifiP2pDevice) peer;
                    peerId = wifiPeer.deviceName;
                    peerName = wifiPeer.deviceName;
                    peerAddress = wifiPeer.deviceAddress;
                    Log.d(TAG, "Forwarding to Wi-Fi Direct peer: " + peerId);

                } else if (peer instanceof BluetoothDevice) {
                    // Bluetooth
                    BluetoothDevice btPeer = (BluetoothDevice) peer;
                    peerId = btPeer.getName();
                    peerName = btPeer.getName();
                    peerAddress = btPeer.getAddress();
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
                    // ✓ Bluetooth forwarding with thread checks
                    boolean hasActiveThread = (bluetoothServerThread != null && bluetoothServerThread.isAlive()) ||
                            (bluetoothClientThread != null && bluetoothClientThread.isAlive());

                    if (!hasActiveThread) {
                        Log.w(TAG, "Cannot forward - no active Bluetooth threads");
                        return;
                    }

                    BluetoothDevice btPeer = (BluetoothDevice) peer;

                    if (currentProtocol.equals("EPIDEMIC")) {
                        epidemicRouting.forwardMessagesBluetooth(messagesToForward, btPeer,
                                bluetoothServerThread, bluetoothClientThread);
                    } else if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                        sprayAndWaitRouting.forwardMessagesBluetooth(messagesToForward, btPeer,
                                bluetoothServerThread, bluetoothClientThread);
                    }

                    Log.d(TAG, "✓✓✓ Forwarded " + messagesToForward.size() + " messages via Bluetooth");

                } else {
                    // ✓ Wi-Fi Direct forwarding with thread checks
                    boolean hasActiveThread = (serverThread != null && serverThread.isConnected()) ||
                            (clientThread != null && clientThread.isConnected());

                    if (!hasActiveThread) {
                        Log.w(TAG, "Cannot forward - no active Wi-Fi Direct threads");
                        return;
                    }

                    WifiP2pDevice wifiPeer = (WifiP2pDevice) peer;

                    activeRoutingProtocol.forwardMessages(messagesToForward, wifiPeer,
                            serverThread, clientThread);

                    Log.d(TAG, "✓✓✓ Forwarded " + messagesToForward.size() + " messages via Wi-Fi Direct");
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

                runOnUiThread(() -> {
                    chatMessages.clear();

                    // ✓ Get my device name once
                    String myDeviceName = getMyDeviceName();
                    Log.d(TAG, "My device name: " + myDeviceName);

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
                        boolean isFromMe = msg.source_id.equals(myDeviceName);
                        boolean isForMe = msg.destination_id.equals(myDeviceName);

                        if (!isFromMe && !isForMe) {
                            Log.d(TAG, "Skipping transit message: " + msg.message_id);
                            continue;
                        }

                        try {
                            String decryptedText = CryptoUtils.decrypt(msg.encrypted_payload);

                            String displayText;
                            if (isFromMe) {
                                // ✓ CHANGED: Show source device name
                                displayText = "Me: " + decryptedText;
                            } else {
                                // ✓ CHANGED: Show as "DeviceName: message"
                                displayText = msg.source_id + ": " + decryptedText;
                            }

                            ChatMessage chatMessage = new ChatMessage(
                                    displayText,
                                    msg.message_id,
                                    msg.is_delivered
                            );
                            chatMessages.add(chatMessage);

                            Log.d(TAG, "Added message: " + displayText);

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


        // CHECK FOR DUPLICATES
        Message existing = messageDao.getMessageById(message.message_id);
        if (existing != null) {
            Log.d(TAG, "Duplicate message received, ignoring");
            return; // ← Skip if already received
        }

        // SAVE TO DATABASE
        messageDao.insert(message);
        Log.d(TAG, "New message stored in database");

        // Try to get saved Device Name
        String myDeviceName = getMyDeviceName();
        Log.d(TAG, "Checking destination...");
        Log.d(TAG, "  My device name: " + myDeviceName);
        Log.d(TAG, "  Message destination: " + message.destination_id);


        // CHECK IF MESSAGE IS FOR ME
        Log.d(TAG, "Checking destination...");
        Log.d(TAG, "  My name : " + myDeviceName);
        Log.d(TAG, "  Message destination: " + message.destination_id);

        boolean isForMe = (myDeviceName != null && myDeviceName.equals(message.destination_id));

        if (isForMe) {
            // ✓ YES - MESSAGE IS FOR ME!
            Log.d(TAG, "✓ Message IS FOR ME!");

            // STEP 4A: DECRYPT MESSAGE
            String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
            Log.d(TAG, "✓ Decrypted: " + decryptedText);

            // STEP 5: DISPLAY IN CHAT (MAIN THREAD)
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

            // STEP 6: LOG DELIVERY
            logger.logEvent("EVENT=MESSAGE_DELIVERED | MSG_ID=" + message.message_id);

            // STEP 7: SEND ACK
            generateAndSendAck(message);

        } else {
            // ✗ NO - MESSAGE IS NOT FOR ME
            Log.d(TAG, "✗ Message NOT for me, forwarding..... ");
            Log.d(TAG, "  From: " + message.source_id);
            Log.d(TAG, "  To: " + message.destination_id);
            Log.d(TAG, "  Current hop: " + message.hop_count);

            // ✓ INCREMENT HOP COUNT
            message.hop_count++;
            messageDao.update(message);

            boolean forwarded = false;

            // ✓ Forward based on active transport
            if (activeTransport == TransportType.BLUETOOTH) {
                // Forward via Bluetooth
                if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
                    bluetoothClientThread.write(message);
                    forwarded = true;
                    Log.d(TAG, "✓ Forwarded via Bluetooth ClientThread");
                }
                if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                    bluetoothServerThread.write(message);
                    forwarded = true;
                    Log.d(TAG, "✓ Forwarded via Bluetooth ServerThread");
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
        }
    }

    private String getMyDeviceName() {
        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            Log.e(TAG, "Device name not initialized!");
            initializeMyDeviceName();
        }
        return ownDeviceId;
    }
    private void initializeMyDeviceName() {
        // Try Bluetooth name first
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            try {
                ownDeviceId = bluetoothAdapter.getName();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to get Bluetooth name");
            }
        }

        // Fallback 1: Android device name
        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            try {
                ownDeviceId = Settings.Global.getString(
                        getContentResolver(),
                        Settings.Global.DEVICE_NAME
                );
            } catch (Exception e) {
                Log.w(TAG, "Cannot get device name");
            }
        }

        // Fallback 2: Build model
        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            // Last resort fallback
            ownDeviceId = Build.MODEL;
        }

        Log.d(TAG, "✓ My device name: " + ownDeviceId);
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

        // ════════════════════════════════════════════════════════════════
        // STRATEGY 1: Get Paired Devices (MOST RELIABLE)
        // ════════════════════════════════════════════════════════════════

        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            if (pairedDevices != null && !pairedDevices.isEmpty()) {
                List<BluetoothDevice> dtnDevices = new ArrayList<>();
                Log.d(TAG, "✓ Found " + pairedDevices.size() + " paired device(s)");

                for (BluetoothDevice device : pairedDevices) {
                    Log.d(TAG, "  • Paired: " + device.getName() + " (" + device.getAddress() + ")");

                    if (isAndroidPhoneWithDTN(device)) {
                        Log.d(TAG, "  ✓ Compatible device found: " + device.getName());
                        dtnDevices.add(device);
                    } else {
                        Log.d(TAG, "  ✗ Skipping: " + device.getName() + " (not DTN compatible)");
                    }
                }
                if (!dtnDevices.isEmpty()) {
                    Log.d(TAG, "Found " + dtnDevices.size() + " DTN-compatible device(s)");

                    discoveredDevices.addAll(dtnDevices);
                    updateBluetoothDeviceList(discoveredDevices);

                    statusTextView.setText("Status: Found " + dtnDevices.size() + " DTN device(s)");
                } else {
                    Log.w(TAG, "No DTN-compatible devices found (found headsets/other devices)");
                    statusTextView.setText("Status: No DTN app on other devices");
                    Toast.makeText(this, "Run DTN app on another device first", Toast.LENGTH_LONG).show();

                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for paired devices: " + e.getMessage());
        }

        // ════════════════════════════════════════════════════════════════
        // STRATEGY 2: Try Classic Bluetooth
        // ════════════════════════════════════════════════════════════════

        Log.d(TAG, "Starting Classic Bluetooth discovery...");

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissions denied");
                return;
            }

            // ✓ Cancel existing discovery
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }

            // ✓ Start new discovery (12 seconds, finds ALL nearby devices)
            boolean started = bluetoothAdapter.startDiscovery();

            if (started) {
                Log.d(TAG, "✓ Active discovery started (will find new devices)");
                statusTextView.setText("Status: Scanning for new devices...");
            } else {
                Log.w(TAG, "Failed to start active discovery");
            }

        } catch (Exception e) {
            Log.e(TAG, "Active discovery failed", e);
        }

        // ════════════════════════════════════════════════════════════════
        // STRATEGY 3: Try BLE Scan
        // ════════════════════════════════════════════════════════════════

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "✓ Starting BLE (Android >= LOLLIPOP)");
            Log.d(TAG, ">>> BEFORE startBLEAdvertising()");

            startBLEAdvertising();  // ✓ Advertise first

            Log.d(TAG, ">>> AFTER startBLEAdvertising(), BEFORE tryBLEScan()");

            tryBLEScan();  // ✓ Then scan

            Log.d(TAG, ">>> AFTER tryBLEScan()");
            return;  // ✓ Exit after BLE
        }

        Log.w(TAG, "No devices found and BLE scan not supported");
        statusTextView.setText("Status: No paired devices found");
    }

    public boolean isAndroidPhoneWithDTN(BluetoothDevice device) {
        try {
            // Get device class to determine type
            int deviceClass = device.getBluetoothClass().getDeviceClass();
            int deviceType = device.getType();

            // ✗ Skip audio devices (headsets, speakers)
            if ((deviceClass & 0x400) != 0) {  // Audio device
                Log.d(TAG, "  Skipping audio device");
                return false;
            }

            // ✗ Skip LE devices (many are not phones)
            if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                Log.d(TAG, "  Skipping BLE-only device");
                return false;
            }

            // ✓ Accept phones and generic computers
            if (deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                    deviceType == BluetoothDevice.DEVICE_TYPE_DUAL) {

                String name = device.getName();

                // ✓ Accept if has reasonable name
                if (name != null && !name.isEmpty() && name.length() < 50) {
                    Log.d(TAG, "  Likely Android phone: " + name);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking device: " + e.getMessage());
            return false;
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
            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String deviceName = device.getName() != null ? device.getName() : "Unknown";
                    int rssi = result.getRssi();
                    Log.d(TAG, "📱📱📱 onScanResult TRIGGERED! Device found!");
                    Log.d(TAG, "Device: " + deviceName + " (" + device.getAddress() + ")");
                    Log.d(TAG, "RSSI: " + rssi + " dBm");

                    // Add to discovered devices list
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device);
                    }
                    // Connect to first found device
//                    connectToBluetoothDevice(device);
                    runOnUiThread(() -> {
                        updateBluetoothDeviceList(discoveredDevices);
                    });

                    // ✓ Stop scanning after first device
                    try {
                        scanner.stopScan(this);
                        Log.d(TAG, "✓ BLE scan stopped (found device)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping BLE scan", e);
                    }
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

            scanner.startScan(null, settings, callback);
            Log.d(TAG, "✓ BLE scan started");
            statusTextView.setText("Status: Scanning for BLE devices...");

            // Stop scan after 15 seconds to save battery
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Log.d(TAG, "Stopping BLE scan (timeout)");
                    scanner.stopScan(callback);
                    Log.d(TAG, "✓ BLE scan stopped (timeout)");

                    if (!isBluetoothConnected) {
                        statusTextView.setText("Status: No BLE devices found");
                        Toast.makeText(MainActivity.this, "No BLE devices found", Toast.LENGTH_SHORT).show();
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
            Log.d(TAG, "✓ Got BluetoothManager");

            // Step 2: Get Bluetooth Adapter
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null) {
                Log.e(TAG, "❌ BluetoothAdapter is null");
                return;
            }
            Log.d(TAG, "✓ Got BluetoothAdapter");

            // Step 3: Check Bluetooth enabled
            if (!adapter.isEnabled()) {
                Log.e(TAG, "❌ Bluetooth is NOT enabled");
                return;
            }
            Log.d(TAG, "✓ Bluetooth is enabled");

            // Step 4: Get BLE Advertiser
            BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.e(TAG, "❌ BluetoothLeAdvertiser is null - BLE NOT supported");
                return;
            }
            Log.d(TAG, "✓ Got BluetoothLeAdvertiser");

            // Step 5: Check permissions (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "Checking BLUETOOTH_ADVERTISE permission (Android 12+)...");
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "❌ BLUETOOTH_ADVERTISE permission not granted");
                    return;
                }
                Log.d(TAG, "✓ BLUETOOTH_ADVERTISE permission granted");
            }

            // Step 6: Create Advertising Data
            AdvertiseData advertisingData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            Log.d(TAG, "✓ Created AdvertiseData with device name");

            // Step 7: Create Advertising Settings
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(2)
                    .setConnectable(true)
                    .build();
            Log.d(TAG, "✓ Created AdvertiseSettings");

            // Step 8: Start Advertising
            Log.d(TAG, "Starting advertiser.startAdvertising()...");

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

            Log.d(TAG, "✓ advertiser.startAdvertising() called (waiting for callback...)");

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

        // 1. Permission check (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ BLUETOOTH_CONNECT permission NOT GRANTED!");
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Bluetooth permission is not granted.\n\nPlease go to Settings → Apps → DTN → Permissions and enable Bluetooth.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .setCancelable(false)
                        .show();
            });
            return;
        }

        final String deviceAddress = device.getAddress();
        final String deviceName = (device.getName() == null || device.getName().trim().isEmpty()) ?
                "Device_" + deviceAddress.replace(":", "") : device.getName();

        // 2. Check if already connecting or connected (safe for multiple threads)
        if (connectedDeviceAddresses.contains(deviceAddress)) {
            Log.w(TAG, "Already connecting/connected to: " + deviceName);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Already connecting...", Toast.LENGTH_SHORT).show());
            return;
        }
        if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
            Log.w(TAG, "Client thread already running");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connection already in progress", Toast.LENGTH_SHORT).show());
            return;
        }

        Log.d(TAG, "Connecting to " + deviceName + " @ " + deviceAddress);

        // 3. Mark as connecting and increment connectionAttemptId
        connectedDeviceAddresses.add(deviceAddress);
        connectedBluetoothDeviceName = deviceName;
        isBluetoothConnected = false;
        final int currentAttemptId = ++connectionAttemptId;

        // 4. Ensure server thread is running
        if (bluetoothServerThread == null || !bluetoothServerThread.isAlive()) {
            Log.e(TAG, "⚠ Server thread not running! This shouldn't happen.");
            // Start as fallback
            bluetoothServerThread = new BluetoothServerThread(bluetoothAdapter, handler);
            bluetoothServerThread.start();
            Log.d(TAG, "✓ Server thread started");
        }

        // 5. Start client thread
        bluetoothClientThread = new BluetoothClientThread(device, handler);
        bluetoothClientThread.start();
        Log.d(TAG, "✓ Client thread started for " + deviceName);

        // 6. After 3s, check if connection is valid
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (currentAttemptId != connectionAttemptId) {
                Log.d(TAG, "Ignoring old connection check (new attempt started)");
                return;
            }

            boolean hasConnectedSocket = bluetoothClientThread != null && bluetoothClientThread.isConnected();

            Log.d(TAG, "Connection check: Socket connected=" + hasConnectedSocket);

            if (hasConnectedSocket) {
                isBluetoothConnected = true;
                runOnUiThread(() -> {
                    statusTextView.setText("Status: ✓ Connected to " + deviceName);
                    statusTextView.setTextColor(0xFF4CAF50);
                    Toast.makeText(MainActivity.this, "✓ Connected to " + deviceName, Toast.LENGTH_SHORT).show();
                });
                Log.d(TAG, "✓✓✓ Connection SUCCESSFUL to " + deviceName);

                // Trigger message forwarding after connection established
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isBluetoothConnected && currentAttemptId == connectionAttemptId) {
                        Log.d(TAG, "Triggering Bluetooth forwarding logic...");
                        triggerForwardingLogic(device);
                    }
                }, 2000);

            } else {
                // Connection failed, clean up
                isBluetoothConnected = false;
                connectedDeviceAddresses.remove(deviceAddress);
                runOnUiThread(() -> {
                    statusTextView.setText("Status: Connection failed to " + deviceName);
                    statusTextView.setTextColor(0xFFF44336);
                    Toast.makeText(MainActivity.this, "Failed to connect to " + deviceName, Toast.LENGTH_SHORT).show();
                });
                Log.e(TAG, "✗✗✗ Connection FAILED to " + deviceName);
                if (bluetoothClientThread != null) {
                    bluetoothClientThread.cancel(); // or close() if that's what your thread uses
                    bluetoothClientThread = null;
                }
            }
        }, 3000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "✓ All permissions granted");
//                // Retry discovery
//                if (activeTransport == TransportType.BLUETOOTH) {
//                    startBluetoothDiscovery();
//                }
            } else {
                Log.w(TAG, "Permissions denied");
                Toast.makeText(this, "Permissions required for Bluetooth", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (wifiDirectBroadcastReceiver != null && wifiDirectIntentFilter != null && !isWifiDirectReceiverRegistered) { // FIXED
            registerReceiver(wifiDirectBroadcastReceiver, wifiDirectIntentFilter); // FIXED
            isWifiDirectReceiverRegistered = true;
            Log.d(TAG, "Wi-Fi Direct receiver registered");
        }

        if (bluetoothReceiver!= null && bluetoothIntentFilter != null && !isBluetoothReceiverRegistered) { // FIXED
            registerReceiver(bluetoothReceiver, bluetoothIntentFilter); // FIXED
            isBluetoothReceiverRegistered = true;
            Log.d(TAG, "Bluetooth receiver registered");
        }

        discoverPeers();
        loadMessagesFromDatabase();
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
        connectedBluetoothDeviceName = null ;

        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
            bluetoothClientThread.close();
            bluetoothClientThread = null;
        }

        if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
            bluetoothServerThread.close();
            bluetoothServerThread = null;
        }

        statusTextView.setText("Status: Switched to Wi-Fi Direct");
        statusTextView.setTextColor(0xFF4CAF50);
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
                    friendDisplayNames.add(friend.friendlyName + " (" + friend.deviceId + ")");
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
                message.source_id = ownDeviceId; // Your device ID
                message.destination_id = friend.deviceId; // Friend's device ID

                // Set message properties
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



    private void sendMessageToDestination(String destinationDeviceName, String messageText) {
        Log.d(TAG, "Sending message to: " + destinationDeviceName);

        executor.execute(() -> {
            try {
                final Message message = new Message();
                message.message_id = UUID.randomUUID().toString();
                message.encrypted_payload = CryptoUtils.encrypt(messageText);
                message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);

                // Set source and destination
                message.source_id = getMyDeviceName();
                message.destination_id = destinationDeviceName;  // ✓ Use provided name

                message.priority = 1;
                message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2 hours
                message.hop_count = 0;
                message.copy_count = currentProtocol.equals("SPRAYANDWAIT") ? 6 : 1;

                // Save to database
                messageDao.insert(message);
                Log.d(TAG, "Message saved for: " + destinationDeviceName);

                runOnUiThread(() -> {
                    // Display in chat
                    String displayText = String.format(Locale.US, "Me → %s: %s",
                            destinationDeviceName, messageText);
                    ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, false);
                    chatMessages.add(chatMessage);
                    chatAdapter.notifyDataSetChanged();
                    chatListView.smoothScrollToPosition(chatMessages.size() - 1);

                    // ✓ Send via network (epidemic routing)
                    sendViaNetwork(message);

                    Toast.makeText(MainActivity.this,
                            "Message sent to network for: " + destinationDeviceName,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Error sending message",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    //  to broadcast message to all neighbors
    private void sendViaNetwork(Message message) {
        // Send to ALL connected neighbors (epidemic routing)
        if (activeTransport == TransportType.BLUETOOTH) {
            if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
                bluetoothClientThread.write(message);
                Log.d(TAG, "Sent via Bluetooth ClientThread");
            }
            if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
                bluetoothServerThread.write(message);
                Log.d(TAG, "Sent via Bluetooth ServerThread");
            }
        } else if (activeTransport == TransportType.WIFI_DIRECT) {
            if (serverThread != null && serverThread.isConnected()) {
                serverThread.write(message);
                Log.d(TAG, "Sent via Wi-Fi Direct ServerThread");
            }
            if (clientThread != null && clientThread.isConnected()) {
                clientThread.write(message);
                Log.d(TAG, "Sent via Wi-Fi Direct ClientThread");
            }
        }
    }

    private void openMessageComposer(Friend selectedFriend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Create input field
        final EditText messageInput = new EditText(this);
        messageInput.setHint("Type message for " + selectedFriend.friendlyName);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setLines(3);

        builder.setTitle("Message to " + selectedFriend.friendlyName)
                .setView(messageInput)
                .setPositiveButton("Send", (dialog, which) -> {
                    String messageText = messageInput.getText().toString().trim();

                    if (messageText.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Message is empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // ✓ Send message to selected friend
                    sendMessageToFriend(selectedFriend, messageText);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private boolean isConnectedToDevice(String deviceName) {
        Log.d(TAG, "Checking connection for: " + deviceName);

        if (activeTransport == TransportType.BLUETOOTH) {
            // ✓ For Bluetooth: Check isBluetoothConnected flag and device name
            boolean clientThreadAlive = bluetoothClientThread != null
                    && bluetoothClientThread.isAlive();
            boolean serverThreadAlive = bluetoothServerThread != null
                    && bluetoothServerThread.isAlive();

            boolean isConnected = isBluetoothConnected
                    && connectedBluetoothDeviceName != null
                    && (clientThreadAlive || serverThreadAlive)
                    && (connectedBluetoothDeviceName.equals(deviceName)
                    || connectedBluetoothDeviceName.contains(deviceName));

            Log.d(TAG, "Bluetooth check: isConnected=" + isBluetoothConnected +
                    ", deviceName=" + connectedBluetoothDeviceName +
                    ", match=" + isConnected);

            return isConnected;

        } else if (activeTransport == TransportType.WIFI_DIRECT) {
            // ✓ For Wi-Fi Direct: Check deviceArray and find matching device
            if (deviceArray != null && deviceArray.length > 0) {
                for (WifiP2pDevice device : deviceArray) {
                    if (device != null &&
                            (device.deviceName.equals(deviceName) ||
                                    device.deviceAddress.equals(deviceName))) {

                        Log.d(TAG, "Wi-Fi Direct: Found matching device ✓");
                        return true;
                    }
                }
            }

            Log.d(TAG, "Wi-Fi Direct: No matching device found");
            return false;
        }

        return false;
    }

    private void sendViaConnectedPeer(Message message) {
        try {
            if (activeTransport == TransportType.BLUETOOTH) {
                if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
                    bluetoothClientThread.write(message);
                    Log.d(TAG, "✓ Message sent via Bluetooth to " + message.destination_id);
                }
            } else if (activeTransport == TransportType.WIFI_DIRECT) {
                if (serverThread != null && serverThread.isConnected()) {
                    serverThread.write(message);
                    Log.d(TAG, "✓ Message sent via Wi-Fi Direct to " + message.destination_id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
        }
    }
    // ✓ ADD THIS METHOD in MainActivity
    public void updateBluetoothDeviceList(List<BluetoothDevice> devices) {
        this.discoveredDevices = devices;
        Log.d(TAG, "updateBluetoothDeviceList called with " + devices.size() + " devices");

        runOnUiThread(() -> {
            List<String> deviceNames = new ArrayList<>();

                    for (BluetoothDevice device : devices) {
                        // ✓ Check if DTN device
                        if (isDTNDevice(device)) {
                            String name = device.getName();
                            if (name == null || name.trim().isEmpty()) {
                                name = "Unknown Device";
                            }
                            String address = device.getAddress();

                            // ✓ Add to list (only if DTN device)
                            if (connectedDeviceAddresses.contains(address)) {
                                deviceNames.add(name + " (Connected)");
                            } else {
                                deviceNames.add(name + " (" + address + ")");
                            }

                            Log.d(TAG, "  ✓ Added DTN device: " + name);
                        } else {
                            Log.d(TAG, "  ✗ Skipped non-DTN device");
                        }
                    }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    MainActivity.this,
                    android.R.layout.simple_list_item_1,
                    deviceNames
            );

            peerListView.setAdapter(adapter);
            Log.d(TAG, "✓ Peer list updated: " + devices.size() + " devices");
        });
    }

    private boolean isDTNDevice(BluetoothDevice device) {
        // Any Android phone is potential DTN node
        return isAndroidPhoneWithDTN(device);
    }
    public void onBluetoothDiscoveryFinished(List<BluetoothDevice> devices) {

        Log.d(TAG, "onBluetoothDiscoveryFinished: Found " + devices.size() + " devices");

        runOnUiThread(() -> {
            statusTextView.setText("Status: Found " + devices.size() + " devices");

            if (devices.size() > 0) {
                Toast.makeText(MainActivity.this,
                        "✓ Found " + devices.size() + " device(s)",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this,
                        "No devices found",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Log.d(TAG, "✓ Bluetooth enabled by user");
                    startBluetoothDiscovery();
                } else {
                    Log.w(TAG, "User declined to enable Bluetooth");
                    Toast.makeText(this, "Bluetooth required", Toast.LENGTH_SHORT).show();
                }
            });

    private void makeBluetoothDiscoverable() {
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300); // 300 seconds = 5 minutes

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted");
                return;
            }
        }

        startActivity(discoverableIntent);
        Log.d(TAG, "✓ Device is now discoverable for 5 minutes");

        Toast.makeText(this, "✓ This device is now discoverable", Toast.LENGTH_LONG).show();
    }
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

                // Repeat every 30 seconds
                discoveryHandler.postDelayed(this, 120000);
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

        if (isBluetoothConnected) {
            Log.d(TAG, "Already connected, skipping auto-connect");
            return;
        }

        executor.execute(() -> {
            // Get friends from database
            List<Friend> friends = friendDao.getAllFriends();
            Set<String> friendAddresses = new HashSet<>();

            for (Friend friend : friends) {
                friendAddresses.add(friend.deviceId);
            }

            // Find first discovered device that is a friend
            for (BluetoothDevice device : discoveredDevices) {
                if (friendAddresses.contains(device.getAddress())) {
                    String deviceName = device.getName() != null ? device.getName() : "Unknown";
                    Log.d(TAG, "Auto-connecting to friend: " + deviceName);

                    runOnUiThread(() -> {
                        connectToBluetoothDevice(device);
                    });

                    break; // Connect to first friend found
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryHandler != null && discoveryRunnable != null){
            discoveryHandler.removeCallbacks(discoveryRunnable);
        }

        // Shutdown threads
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.close();
        }
        if (clientThread != null && clientThread.isAlive()) {
            clientThread.close();
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
                try {
                    unregisterReceiver(bluetoothReceiver);
                    isBluetoothReceiverRegistered = false;
                    Log.d(TAG, "✓ Bluetooth receiver unregistered");
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Receiver not registered", e);
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error unregistering receivers", e);
        }
        // shut bluetooth
        if (bluetoothReceiver != null) {
            unregisterReceiver(bluetoothReceiver);
        }

        if (bluetoothClientThread != null) {
            bluetoothClientThread.close();
        }

        if (bluetoothServerThread != null) {
            bluetoothServerThread.close();
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }

        Log.d(TAG, "MainActivity destroyed - cleanup complete");
    }



}
