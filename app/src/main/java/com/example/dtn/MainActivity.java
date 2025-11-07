package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

    private final Queue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean isDeviceIdReady = false;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothBroadcastReceiver bluetoothReceiver;
    private IntentFilter bluetoothIntentFilter;
    private BluetoothServerThread bluetoothServerThread;
    private BluetoothClientThread bluetoothClientThread;
    private volatile boolean isBluetoothConnected = false;
    private String connectedBluetoothDeviceName = null;
    private List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private boolean isBluetoothReceiverRegistered = false;
    private Handler discoveryHandler = new Handler(Looper.getMainLooper());
    private Handler discoveryRetryHandler = new Handler(Looper.getMainLooper());
    private Runnable discoveryRunnable;

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
        initializeBluetooth();// sets up bluetooth


        // Chooses which transport to use (Wi-Fi Direct or Bluetooth)
        selectBestTransport();
        Log.d(TAG, "onCreate() completed. Initial ownDeviceId: " + ownDeviceId);

        discoveryRunnable = () -> {
            // ✓ CHECK ACTIVE TRANSPORT FIRST!
            if (activeTransport == TransportType.WIFI_DIRECT) {
                // Wi-Fi Direct discovery
                if (manager != null && channel != null) {
                    Log.d(TAG, "🔍 Starting Wi-Fi Direct discovery...");
                    manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "✓ Wi-Fi Direct discovery initiated");
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.w(TAG, "Wi-Fi Direct discovery failed (code: " + reason + ")");
                        }
                    });
                }
            }
            else if (activeTransport == TransportType.BLUETOOTH) {
                // Bluetooth discovery
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    Log.d(TAG, "🔍 Starting Bluetooth discovery...");

                    // Cancel previous discovery
                    if (bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }

                    // Start new discovery
                    boolean started = bluetoothAdapter.startDiscovery();
                    if (started) {
                        Log.d(TAG, "✓ Bluetooth discovery initiated");
                    } else {
                        Log.w(TAG, "Bluetooth discovery failed to start");
                    }
                } else {
                    Log.w(TAG, "Bluetooth adapter not available or not enabled");
                }
            }

            // Schedule next discovery in 10 seconds
            discoveryHandler.postDelayed(discoveryRunnable, 10000);
        };

        // Start discovery immediately
        discoveryHandler.post(discoveryRunnable);

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

            // ════════════════════════════════════════════════════════════════
            // FROM WI-FI DIRECT THREADS
            // ════════════════════════════════════════════════════════════════

            if (msg.what == ServerThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Wi-Fi Direct ServerThread");

                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    Log.d(TAG, "Handler received (Wi-Fi Direct): " + receivedMessage.message_id);
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }

            // ════════════════════════════════════════════════════════════════
            // FROM BLUETOOTH THREADS
            // ════════════════════════════════════════════════════════════════

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
                        .setPositiveButton("Yes", (dialog, which) -> addWifiDirectFriend(device))
                        .setNegativeButton("No", null)
                        .show();
            } else {
//                if (bluetoothPeers == null || position >= bluetoothPeers.size()) return false;
//                final BluetoothDevice device = bluetoothPeers.get(position);
//                new AlertDialog.Builder(this)
//                        .setTitle("Add Friend")
//                        .setMessage("Add " + device.getName() + " as friend?")
//                        .setPositiveButton("Yes", (dialog, which) -> addBluetoothFriend(device))
//                        .setNegativeButton("No", null)
//                        .show();
            }
            return true;
        });

        peerListView.setOnItemClickListener((parent, view, position, id) -> { //  Connect to selected peer
            if (activeTransport == TransportType.WIFI_DIRECT) {
                connectToWifiDirectDevice(position);
            } else {
//                connectToBluetoothDevice(position);
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
            targetDevice = connectedBluetoothDeviceName;  // ← Set this when connected

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

    private void addWifiDirectFriend(final WifiP2pDevice device) {
        executor.execute(() -> {
            try {
                Friend existingFriend = friendDao.getFriendById(device.deviceAddress);
                if (existingFriend == null) {
                    Friend newFriend = new Friend();
                    newFriend.deviceId = device.deviceAddress;
                    newFriend.friendlyName = device.deviceName;
                    newFriend.lastEncounteredTimestamp = 0;
                    friendDao.insert(newFriend);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, device.deviceName +
                            " added as friend", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, device.deviceName +
                            " already a friend", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error adding Wi-Fi Direct friend", e);
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
                boolean isBluetoothPeer = false;

                if (peer instanceof WifiP2pDevice) {
                    // Wi-Fi Direct
                    WifiP2pDevice wifiPeer = (WifiP2pDevice) peer;
                    peerId = wifiPeer.deviceAddress;
                    peerAddress = wifiPeer.deviceAddress;
                    Log.d(TAG, "Forwarding to Wi-Fi Direct peer: " + peerId);

                } else if (peer instanceof BluetoothDevice) {
                    // Bluetooth
                    BluetoothDevice btPeer = (BluetoothDevice) peer;
                    peerId = btPeer.getName();
                    peerAddress = btPeer.getAddress();
                    isBluetoothPeer = true;
                    Log.d(TAG, "Forwarding to Bluetooth peer: " + peerId);

                } else {
                    Log.e(TAG, "Unknown peer type");
                    return;
                }

                // Record friend encounter
                final Friend connectedFriend = friendDao.getFriendById(peerId);
                if (connectedFriend != null) {
                    connectedFriend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(connectedFriend);
                }


                // Get all non-expired messages
                final List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                final List<Message> messagesToForward = new ArrayList<>();

                // Filter messages using contact history
                for (Message message : allMessages) {
                    // If message is FOR this device, send it
                    if (peerId.equals(message.destination_id)) {
                        messagesToForward.add(message);
                        continue;
                    }

                    // Only forward if connected peer recently saw destination
                    Friend destinationFriend = friendDao.getFriendById(message.destination_id);
                    if (destinationFriend != null && connectedFriend != null) {
                        if (connectedFriend.lastEncounteredTimestamp > destinationFriend.lastEncounteredTimestamp) {
                            messagesToForward.add(message);
                        }
                    }
                }

                // Sort messages by priority and TTL
                Collections.sort(messagesToForward, (m1, m2) -> {
                    int priorityCompare = Integer.compare(m2.priority, m1.priority);
                    return priorityCompare != 0 ? priorityCompare : Long.compare(m2.ttl_timestamp, m1.ttl_timestamp);
                });

                // Initialize routing protocol
                if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                    activeRoutingProtocol = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao);
                    sprayAndWaitRouting = (SprayAndWaitRouting) activeRoutingProtocol;
                } else {
                    activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId, messageDao);
                    epidemicRouting = (EpidemicRouting) activeRoutingProtocol;
                }

                // Forward messages based on transport type
                if (activeRoutingProtocol != null && !messagesToForward.isEmpty()) {
                    if (isBluetoothPeer) {
                        // Bluetooth forwarding
                        BluetoothDevice btPeer = (BluetoothDevice) peer;

                        if (currentProtocol.equals("EPIDEMIC")) {
                            epidemicRouting.forwardMessagesBluetooth(messagesToForward, btPeer,
                                    bluetoothServerThread, bluetoothClientThread);
                        } else if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                            sprayAndWaitRouting.forwardMessagesBluetooth(messagesToForward, btPeer,
                                    bluetoothServerThread, bluetoothClientThread);
                        }

                        Log.d(TAG, "✓ Forwarded " + messagesToForward.size() + " messages via Bluetooth");

                    } else {
                        // Wi-Fi Direct forwarding
                        WifiP2pDevice wifiPeer = (WifiP2pDevice) peer;

                        activeRoutingProtocol.forwardMessages(messagesToForward, wifiPeer,
                                serverThread, clientThread);

                        Log.d(TAG, "✓ Forwarded " + messagesToForward.size() + " messages via Wi-Fi Direct");
                    }
                } else {
                    Log.d(TAG, "No messages to forward or protocol not initialized");
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

        // ═══════════════════════════════════════════════════════════════════
        // STEP 1: CHECK FOR DUPLICATES
        // ═══════════════════════════════════════════════════════════════════
        Message existing = messageDao.getMessageById(message.message_id);
        if (existing != null) {
            Log.d(TAG, "Duplicate message received, ignoring");
            return; // ← Skip if already received
        }

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2: SAVE TO DATABASE
        // ═══════════════════════════════════════════════════════════════════
        messageDao.insert(message);
        Log.d(TAG, "New message stored in database");

        // ═══════════════════════════════════════════════════════════════════
        // CHANGE 2: GET ACTUAL DEVICE MAC
        // ═══════════════════════════════════════════════════════════════════

        String myDeviceMac = null;

        // Try to get saved Device Name
        String myDeviceName = getMyDeviceName();
        Log.d(TAG, "Checking destination...");
        Log.d(TAG, "  My device name: " + myDeviceName);
        Log.d(TAG, "  Message destination: " + message.destination_id);
//        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
//        myDeviceMac = prefs.getString("ACTUAL_DEVICE_MAC", null);

//        if (myDeviceMac == null) {
//            Log.w(TAG, "⚠️ MAC not available yet (waiting for Wi-Fi Direct)");
//        } else {
//            Log.d(TAG, "✓ Got my MAC: " + myDeviceMac);
//        }

        // ═══════════════════════════════════════════════════════════════════
        // STEP 3: CHECK IF MESSAGE IS FOR ME
        // ═══════════════════════════════════════════════════════════════════

        Log.d(TAG, "Checking destination...");
        Log.d(TAG, "  My name : " + myDeviceName);
        Log.d(TAG, "  Message destination: " + message.destination_id);

        // CHANGE 3: Use myDeviceMac instead of ownDeviceId for comparison
        boolean isForMe = (myDeviceName!= null && myDeviceName.equals(message.destination_id));

        if (isForMe) {
            // ✓ YES - MESSAGE IS FOR ME!
            Log.d(TAG, "✓ Message IS FOR ME!");

            // ═══════════════════════════════════════════════════════════════
            // STEP 4A: DECRYPT MESSAGE
            // ═══════════════════════════════════════════════════════════════
            String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
            Log.d(TAG, "✓ Decrypted: " + decryptedText);

            // ═══════════════════════════════════════════════════════════════
            // STEP 5: DISPLAY IN CHAT (MAIN THREAD)
            // ═══════════════════════════════════════════════════════════════
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

            // ═══════════════════════════════════════════════════════════════
            // STEP 6: LOG DELIVERY
            // ═══════════════════════════════════════════════════════════════
            logger.logEvent("EVENT=MESSAGE_DELIVERED | MSG_ID=" + message.message_id);

            // ═══════════════════════════════════════════════════════════════
            // STEP 7: SEND ACK
            // ═══════════════════════════════════════════════════════════════
            generateAndSendAck(message);

        } else {
            // ✗ NO - MESSAGE IS NOT FOR ME
            Log.d(TAG, "✗ Message NOT for me");

            // CHANGE 4: Only forward if we're not the destination
            // Log the reason why we're forwarding
            if (myDeviceName == null) {
                Log.w(TAG, "⚠️ My MAC unknown yet, queuing for later");
            } else {
                Log.d(TAG, "Message for " + message.destination_id +
                        ", I am " + myDeviceName);
            }

            // ═══════════════════════════════════════════════════════════════
            // STEP 4B: FORWARD MESSAGE TO NEXT PEER
            // ═══════════════════════════════════════════════════════════════
            WifiP2pDevice connectedPeer = findConnectedPeer();
            if (connectedPeer != null) {
                Log.d(TAG, "Forwarding to peer: " + connectedPeer.deviceName);
                triggerForwardingLogic(connectedPeer);
            } else {
                Log.w(TAG, "No peers available for forwarding");
            }
        }
    }
    private void initializeMyDeviceName() {
        // Get this device's Bluetooth name
        ownDeviceId = bluetoothAdapter.getName();

        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            // Fallback to Android device name
            ownDeviceId = Settings.Secure.getString(
                    getContentResolver(),
                    "bluetooth_name"
            );
        }

        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            // Last resort fallback
            ownDeviceId = Build.MODEL;
        }

        Log.d(TAG, "✓ My device name: " + ownDeviceId);
    }
    private String getMyDeviceName() {
        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            Log.e(TAG, "Device name not initialized!");
            initializeMyDeviceName();
        }
        return ownDeviceId;
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
            discoverBluetoothPeers();
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

    private void discoverBluetoothPeers() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Log.d(TAG, "Starting Bluetooth peer discovery");
        bluetoothAdapter.startDiscovery();
        statusTextView.setText("Status: Discovering (Bluetooth)...");
        statusTextView.setTextColor(0xFFFFC107);
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

                    // Connect to first compatible device
                    connectToBluetoothDevice(dtnDevices.get(0));

                    statusTextView.setText("Status: Found " + dtnDevices.size() + " DTN device(s)");
                    return;
                } else {
                    Log.w(TAG, "No DTN-compatible devices found (found headsets/other devices)");
                    statusTextView.setText("Status: No DTN app on other devices");
                    Toast.makeText(this, "Run DTN app on another device first", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for paired devices: " + e.getMessage());
        }

        // ════════════════════════════════════════════════════════════════
        // STRATEGY 2: Try BLE Scan (FALLBACK FOR MODERN DEVICES)
        // ════════════════════════════════════════════════════════════════

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tryBLEScan();
        }else{
            Log.w(TAG, "No devices found and BLE not supported");
            statusTextView.setText("Status: No DTN devices found");
            Toast.makeText(this, "No DTN devices found - pair a device first", Toast.LENGTH_LONG).show();
        }

        Log.w(TAG, "No devices found and BLE scan not supported");
        statusTextView.setText("Status: No paired devices found");
    }
    /**
     * Check if device is likely an Android phone with DTN app
     * (not a headset, speaker, watch, etc.)
     */
    private boolean isAndroidPhoneWithDTN(BluetoothDevice device) {
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

        try {
            BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();

            if (scanner == null) {
                Log.w(TAG, "BLE scanner not available");
                return;
            }

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String deviceName = device.getName() != null ? device.getName() : "Unknown";
                    Log.d(TAG, "✓ Found (BLE): " + deviceName + " (" + device.getAddress() + ")");

                    // Connect to first found device
                    connectToBluetoothDevice(device);

                    // Stop scanning after first device
                    scanner.stopScan(this);
                }
            };

            scanner.startScan(null, settings, callback);
            Log.d(TAG, "✓ BLE scan started");
            statusTextView.setText("Status: Scanning for BLE devices...");

        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "BLE scan error: " + e.getMessage());
        }
    }

    private void attemptBluetoothDiscoveryLegacy() {
        // Attempt discovery
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(this::attemptBluetoothDiscoveryLegacy, 500);
                return;
            }

            boolean started = bluetoothAdapter.startDiscovery();

            if (started) {
                Log.d(TAG, "✓ Discovery started (if supported)");
            } else {
                Log.w(TAG, "Device doesn't support discovery - use paired devices instead");
            }
        } catch (Exception e) {
            Log.w(TAG, "Discovery not supported: " + e.getMessage());
        }
    }

    // Called when devices found
    public void updateBluetoothDeviceList(List<BluetoothDevice> devices) {
        this.bluetoothDevices = devices;
        Log.d(TAG, "Updated device list: " + devices.size() + " devices");
        // Update UI here
    }
    // Called when discovery finishes
    public void onBluetoothDiscoveryFinished(List<BluetoothDevice> devices) {
        Log.d(TAG, "Discovery finished with " + devices.size() + " devices");
        statusTextView.setText("Found: " + devices.size() + " devices");
    }
    // Called when device is paired
    public void onBluetoothDevicePaired(BluetoothDevice device) {
        Log.d(TAG, "Device paired: " + device.getName());
        connectToBluetoothDevice(device);
    }
    // Handle Bluetooth state changes
    public void onBluetoothStateChanged(boolean enabled) {
        if (enabled) {
            Log.d(TAG, "Bluetooth is ON - starting discovery");
            startBluetoothDiscovery();
        } else {
            Log.w(TAG, "Bluetooth is OFF");
        }
    }

    // Connect to specific device
    public void connectToBluetoothDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Device is null");
            return;
        }

        Log.d(TAG, "Connecting to: " + device.getName() + " (" + device.getAddress() + ")");
        isBluetoothConnected = false;
        connectedBluetoothDeviceName = device.getName();

        runOnUiThread(() -> {
            statusTextView.setText("Status: Connecting to " + device.getName() + "...");
            statusTextView.setTextColor(0xFFFFC107);  // Yellow/Orange
        });

        Log.d(TAG, "Stored connected device name: " + connectedBluetoothDeviceName);

        // Close existing connections
        if (bluetoothClientThread != null && bluetoothClientThread.isAlive()) {
            bluetoothClientThread.close();
            bluetoothClientThread = null;
        }

        if (bluetoothServerThread != null && bluetoothServerThread.isAlive()) {
            bluetoothServerThread.close();
            bluetoothServerThread = null;
        }
        // Also start server for receiving messages
        bluetoothServerThread = new BluetoothServerThread(bluetoothAdapter, handler);
        bluetoothServerThread.start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "Now starting client thread...");

            // Then start client (after server is ready)
            bluetoothClientThread = new BluetoothClientThread(device, handler);
            bluetoothClientThread.start();

            Log.d(TAG, "✓ Client thread started");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isBluetoothConnected = true;  // ✓ Mark as connected

                runOnUiThread(() -> {
                    statusTextView.setText("Status: ✓ Connected to " + device.getName());
                    statusTextView.setTextColor(0xFF4CAF50);  // Green
                    Toast.makeText(MainActivity.this, "Connected!", Toast.LENGTH_SHORT).show();
                });
            }, 3000);

        }, 1000);
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
                // Retry discovery
                if (activeTransport == TransportType.BLUETOOTH) {
                    startBluetoothDiscovery();
                }
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

//        if (bluetoothBroadcastReceiver != null && bluetoothIntentFilter != null && !isBluetoothReceiverRegistered) { // FIXED
//            registerReceiver(bluetoothBroadcastReceiver, bluetoothIntentFilter); // FIXED
//            isBluetoothReceiverRegistered = true;
//            Log.d(TAG, "Bluetooth receiver registered");
//        }

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

            // Switch transport
            activeTransport = TransportType.BLUETOOTH;
            updateTransportDisplay();

            // Initialize Bluetooth (if not already)
            if (bluetoothAdapter == null || !isBluetoothReceiverRegistered) {
                initializeBluetooth();
            } else {
                // Bluetooth already initialized, just start discovery
                startBluetoothDiscovery();
            }

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
    private void attemptBluetoothDiscovery() {
        try {
            boolean started = bluetoothAdapter.startDiscovery();

            if (started) {
                Log.d(TAG, "✓✓✓ Bluetooth discovery started ✓✓✓");
                statusTextView.setText("Status: Discovering Bluetooth devices...");
            } else {
                Log.w(TAG, "startDiscovery() returned false - will retry");

                // Retry after 20 seconds
                discoveryRetryHandler.postDelayed(() -> {
                    Log.d(TAG, "Retrying Bluetooth discovery...");
                    attemptBluetoothDiscovery();
                }, 20000);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "❌ SecurityException: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected exception: " + e.getClass().getName());
            e.printStackTrace();
        }
    }

//    @Override
//    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//        super.onActivityResult(requestCode, resultCode, data);
//
//        if (requestCode == REQUEST_ENABLE_BT) {
//            if (resultCode == RESULT_OK) {
//                Log.d(TAG, "✓ Bluetooth enabled");
//                startBluetoothDiscovery();
//            } else {
//                Log.w(TAG, "User declined to enable Bluetooth");
//                Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show();
//            }
//        }
//    }

    private void showFriendsDialog() {
        executor.execute(() -> {
            List<Friend> friends = friendDao.getAllFriends();
            runOnUiThread(() -> {
                if (friends.isEmpty()) {
                    Toast.makeText(this, "No friends yet", Toast.LENGTH_SHORT).show();
                    return;
                }

                StringBuilder friendsList = new StringBuilder();
                for (Friend friend : friends) {
                    friendsList.append("📱 ").append(friend.friendlyName)
                            .append("\n   ").append(friend.deviceId)
                            .append("\n\n");
                }

                new AlertDialog.Builder(this)
                        .setTitle("Friends (" + friends.size() + ")")
                        .setMessage(friendsList.toString())
                        .setPositiveButton("OK", null)
                        .show();
            });
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


    @Override
    protected void onDestroy() {
        super.onDestroy();
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
//            if (isBluetoothReceiverRegistered && bluetoothBroadcastReceiver != null) {
//                unregisterReceiver(bluetoothBroadcastReceiver);
//                isBluetoothReceiverRegistered = false;
//            }
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
