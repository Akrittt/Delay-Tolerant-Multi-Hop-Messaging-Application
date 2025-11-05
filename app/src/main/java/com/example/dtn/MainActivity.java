package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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

    // --- Bluetooth ---
    BluetoothAdapter bluetoothAdapter;
    BroadcastReceiver bluetoothBroadcastReceiver; // FIXED: renamed from bluetoothReceiver
    IntentFilter bluetoothIntentFilter;
    List<BluetoothDevice> bluetoothPeers = new ArrayList<>();
    private boolean isBluetoothReceiverRegistered = false;

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
    EpidemicRouting epidemicRouting; // FIXED: Added these fields
    SprayAndWaitRouting sprayAndWaitRouting; // FIXED: Added these fields
    public static final String PREFS_NAME = "DTNPrefs";
    public static final String KEY_ROUTING_PROTOCOL = "RoutingProtocol";
    private String currentProtocol = "EPIDEMIC";
    public String ownDeviceId = null; // store MAC

    private final Queue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean isDeviceIdReady = false;

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

        // Try to load previously saved device ID
//        String savedDeviceId = prefs.getString("SAVED_DEVICE_ID", null);
//        if (savedDeviceId != null && !savedDeviceId.isEmpty()) {
//            ownDeviceId = savedDeviceId;
//            Log.d(TAG, "✅ Loaded MAC from storage: " + ownDeviceId);
//        } else {
//            Log.d(TAG, "MAC not available yet, will be set on Wi-Fi Direct connection");
//        }

        initializeUI(); // Calls findViewById() to link Java variables to XML layout elements
        updateProtocolDisplay(); // Updates the UI to show current protocol
        updateTransportDisplay(); // Updates the UI to show current transport
        setupPrioritySpinner(); // Initializes the priority dropdown menu
        initializeDatabase(); // Creates/opens Room database
        logger = Logger.getInstance(getApplicationContext()); // All threads can log to same file
        initializeHandler(); // Creates a Handler for message passing between threads
        setListeners(); // Attaches click listeners to UI buttons
        requestPermissions(); // Asks user for runtime permissions (Android 6+)



        // Initialize both transports
        initializeWifiDirect(); // Sets up Wi-Fi Direct




        // Chooses which transport to use (Wi-Fi Direct or Bluetooth)
        selectBestTransport();


        // Gets the unique MAC address for this device
//        initializeDeviceIdSync();
        Log.d(TAG, "onCreate() completed. Initial ownDeviceId: " + ownDeviceId);


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
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE); // 1. Gets WifiP2pManager from system
        if (manager == null) {
            Log.w(TAG, "Wi-Fi Direct not supported");
            return;
        }

        channel = manager.initialize(this, getMainLooper(), null); // 2. Initializes Channel for communication
        if (channel == null) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct channel");
            return;
        }

        wifiDirectBroadcastReceiver = new WifiDirectBroadcastReceiver(manager, channel, this); // 3. Creates WifiDirectBroadcastReceiver
        wifiDirectIntentFilter = new IntentFilter(); // 4. Sets up intent filters for broadcasts
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
        // Handler receives message from background thread
        handler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == ServerThread.MESSAGE_READ) {
                // msg.what = MESSAGE_READ (value = 1)
                // msg.obj = our Message object

                Message receivedMessage = (Message) msg.obj;
                // receivedMessage now on MAIN thread ✓

                Log.d(TAG, "Handler received: " + receivedMessage.message_id);

                // Pass to main message handler
                handleReceivedMessage(receivedMessage);
            }
            return true;
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
                if (bluetoothPeers == null || position >= bluetoothPeers.size()) return false;
                final BluetoothDevice device = bluetoothPeers.get(position);
                new AlertDialog.Builder(this)
                        .setTitle("Add Friend")
                        .setMessage("Add " + device.getName() + " as friend?")
                        .setPositiveButton("Yes", (dialog, which) -> addBluetoothFriend(device))
                        .setNegativeButton("No", null)
                        .show();
            }
            return true;
        });

        peerListView.setOnItemClickListener((parent, view, position, id) -> { //  Connect to selected peer
            if (activeTransport == TransportType.WIFI_DIRECT) {
                connectToWifiDirectDevice(position);
            } else {
                connectToBluetoothDevice(position);
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

    private void connectToBluetoothDevice(int position) {
        if (bluetoothPeers == null || position >= bluetoothPeers.size()) {
            Toast.makeText(this, "Invalid peer selection", Toast.LENGTH_SHORT).show();
            return;
        }

        final BluetoothDevice device = bluetoothPeers.get(position);
        Log.d(TAG, "Connecting to (Bluetooth): " + device.getName());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Toast.makeText(this, "Connecting to " + device.getName(), Toast.LENGTH_SHORT).show();
        onBluetoothDeviceSelected(device);
    }

    private void sendMessage() {
        // 1. GET MESSAGE TEXT FROM INPUT FIELD
        String msgText = messageEditText.getText().toString().trim();
        if (msgText.isEmpty()) {
            Toast.makeText(this, "Message is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. CHECK IF CONNECTED TO A DEVICE
        if (serverThread == null && clientThread == null) {
            Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. GET DESTINATION DEVICE (whoever we're connected to)
        String targetDevice;
        if (activeTransport == TransportType.WIFI_DIRECT) {
            if (deviceArray == null || deviceArray.length == 0) {
                Toast.makeText(this, "No destination peer available", Toast.LENGTH_SHORT).show();
                return;
            }

            // Get first connected peer's Name (not MAC address)
            targetDevice = deviceArray[0].deviceName;
        } else {
            if (bluetoothPeers == null || bluetoothPeers.isEmpty()) {
                Toast.makeText(this, "No destination peer available", Toast.LENGTH_SHORT).show();
                return;
            }
            targetDevice = bluetoothPeers.get(0).getName();
        }

        // ═══════════════════════════════════════════════════════════════════
        // NEW: Check if device ID is ready
        // ═══════════════════════════════════════════════════════════════════

        if (!isDeviceIdReady || ownDeviceId == null || ownDeviceId.contains("-")) {
            // Device ID NOT ready yet - QUEUE the message
            Log.d(TAG, "Device ID not ready, queueing message");
            pendingMessages.offer(new PendingMessage(targetDevice, msgText));

            // Display immediately (with pending status)
            String displayText = String.format(Locale.US, "Me: %s ⏳", msgText);
            ChatMessage chatMessage = new ChatMessage(displayText, UUID.randomUUID().toString(), false);
            chatMessages.add(chatMessage);
            chatAdapter.notifyDataSetChanged();
            chatListView.smoothScrollToPosition(chatMessages.size() - 1);
            messageEditText.setText("");

            Toast.makeText(this, "⏳ Message queued (waiting for device ID)", Toast.LENGTH_SHORT).show();
            return;

        }
        //Device id is ready
        actuallyTransmitMessage(msgText, targetDevice);
    }

    /**
     * Actually transmit a message (after device ID is ready)
     */
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
                if (serverThread != null && serverThread.isConnected()) {
                    serverThread.write(message);
                    sent = true;
                } else if (clientThread != null && clientThread.isConnected()) {
                    clientThread.write(message);
                    sent = true;
                }

                if (sent) {
                    Log.d(TAG, "✓ Message transmitted: " + message.message_id);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
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


//    public void onBluetoothDeviceDiscovered(final BluetoothDevice device) {
//        if (!bluetoothPeers.contains(device)) {
//            bluetoothPeers.add(device);
//            updateBluetoothPeerList();
//            String deviceName = "Unknown";
//            try {
//                deviceName = device.getName();
//            } catch (SecurityException e) {
//                Log.e(TAG, "SecurityException getting device name", e);
//            }
//            Log.d(TAG, "Bluetooth device discovered: " + deviceName);
//        }
//    }

    public void onBluetoothDeviceSelected(final BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not granted");
                    return;
                }
            }
            Log.d(TAG, "Bluetooth device selected: " + device.getName());
            Toast.makeText(this, "Bluetooth connection (coming soon)", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in onBluetoothDeviceSelected", e);
        }
    }

//    public void onBluetoothDiscoveryFinished() {
//        Log.d(TAG, "Bluetooth discovery finished");
//    }
//
//    private void updateBluetoothPeerList() {
//        try {
//            String[] bluetoothDeviceNames = new String[bluetoothPeers.size()];
//            for (int i = 0; i < bluetoothPeers.size(); i++) {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
//                            == PackageManager.PERMISSION_GRANTED) {
//                        bluetoothDeviceNames[i] = bluetoothPeers.get(i).getName();
//                    } else {
//                        bluetoothDeviceNames[i] = "Unknown Device";
//                    }
//                } else {
//                    bluetoothDeviceNames[i] = bluetoothPeers.get(i).getName();
//                }
//            }
//
//            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
//                    android.R.layout.simple_list_item_1, bluetoothDeviceNames);
//            peerListView.setAdapter(adapter);
//
//            Log.d(TAG, "Updated Bluetooth peer list: " + bluetoothPeers.size() + " devices");
//        } catch (SecurityException e) {
//            Log.e(TAG, "SecurityException updating Bluetooth peer list", e);
//        }
//    }

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

    private void addBluetoothFriend(final BluetoothDevice device) {
        executor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }
                }

                String deviceId = device.getAddress();
                Friend existingFriend = friendDao.getFriendById(deviceId);
                if (existingFriend == null) {
                    Friend newFriend = new Friend();
                    newFriend.deviceId = deviceId;
                    newFriend.friendlyName = device.getName();
                    newFriend.lastEncounteredTimestamp = 0;
                    friendDao.insert(newFriend);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, device.getName() +
                            " added as friend", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, device.getName() +
                            " already a friend", Toast.LENGTH_SHORT).show());
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException adding Bluetooth friend", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Permission denied", Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                Log.e(TAG, "Error adding Bluetooth friend", e);
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

    private void triggerForwardingLogic(final WifiP2pDevice peer) {
        executor.execute(() -> {
            try {
                //find friend in database
                final Friend connectedFriend = friendDao.getFriendById(peer.deviceAddress);
                if (connectedFriend != null) {
                    // record when we saw this device
                    connectedFriend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(connectedFriend);
                }

                //get all non-expired messages
                final List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                final List<Message> messagesToForward = new ArrayList<>();

                //prevents infinite loop , use contact history and don't forward to everyone
                for (Message message : allMessages) {
                    // if message is for this device, send it !!!
                    if (peer.deviceAddress.equals(message.destination_id)) {
                        messagesToForward.add(message);
                        continue;
                    }

                    // only forward if connected peer recently saw destination
                    Friend destinationFriend = friendDao.getFriendById(message.destination_id);
                    if (destinationFriend != null && connectedFriend != null) {
                        if (connectedFriend.lastEncounteredTimestamp > destinationFriend.lastEncounteredTimestamp) {
                            messagesToForward.add(message);
                        }
                    }
                }

                // sort messages
                Collections.sort(messagesToForward, (m1, m2) -> {
                    // sort by priority
                    int priorityCompare = Integer.compare(m2.priority, m1.priority);
                    // sort by TTL
                    return priorityCompare != 0 ? priorityCompare : Long.compare(m2.ttl_timestamp, m1.ttl_timestamp);
                });

                // Pass messageDao to routing protocols
                if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                    activeRoutingProtocol = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao);
                    sprayAndWaitRouting = (SprayAndWaitRouting) activeRoutingProtocol;
                } else {
                    activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId, messageDao);
                    epidemicRouting = (EpidemicRouting) activeRoutingProtocol;
                }

                // actual forward message
                // check if ready to forward
                if (activeRoutingProtocol != null && !messagesToForward.isEmpty()) {
                    activeRoutingProtocol.forwardMessages(messagesToForward, peer, serverThread, clientThread);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in forwarding logic", e);
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

                    for (Message msg : allMessages) {
                        boolean isFromMe = msg.source_id.equals(ownDeviceId);
                        boolean isForMe = msg.destination_id.equals(ownDeviceId);

                        if (!isFromMe && !isForMe) {
                            Log.d(TAG, "Skipping transit message: " + msg.message_id);
                            continue;
                        }

                        try {
                            // FIXED: Added Context parameter
                            String decryptedText = CryptoUtils.decrypt(msg.encrypted_payload);

                            String displayText;
                            if (isFromMe) {
                                displayText = "Me: " + decryptedText;
                            } else {
                                displayText = "Peer: " + decryptedText;
                            }

                            ChatMessage chatMessage = new ChatMessage(
                                    displayText,
                                    msg.message_id,
                                    msg.is_delivered
                            );
                            chatMessages.add(chatMessage);

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
    /**
     * HELPER METHOD: Ensure device ID is set
     * Waits synchronously for device info callback
     */
//    private void ensureDeviceIdIsSet() {
//        if (activeTransport == TransportType.WIFI_DIRECT) {
//            if (manager == null || channel == null) {
//                Log.w(TAG, "Manager or channel is null");
//                return;
//            }
//
//            try {
//                // Use lock for synchronous waiting
//                final Object lock = new Object();
//                final String[] resultHolder = new String[1];
//
//                manager.requestDeviceInfo(channel, device -> {
//                    synchronized (lock) {
//                        if (device != null && device.deviceAddress != null) {
//                            resultHolder[0] = device.deviceAddress;
//                            Log.d(TAG, "Got device info: " + resultHolder[0]);
//                        }
//                        lock.notifyAll();  // Wake up waiting thread
//                    }
//                });
//
//                // Wait for callback with timeout
//                synchronized (lock) {
//                    try {
//                        lock.wait(2000);  // Max 2 seconds wait
//
//                        if (resultHolder[0] != null) {
//                            ownDeviceId = resultHolder[0];
//                            Log.d(TAG, "✅ Device ID updated: " + ownDeviceId);
//                        }
//                    } catch (InterruptedException e) {
//                        Log.e(TAG, "Interrupted while waiting for device ID");
//                        Thread.currentThread().interrupt();
//                    }
//                }
//            } catch (Exception e) {
//                Log.e(TAG, "Error getting device ID", e);
//            }
//        } else if (activeTransport == TransportType.BLUETOOTH) {
//            try {
//                if (bluetoothAdapter != null) {
//                    ownDeviceId = bluetoothAdapter.getAddress();
//                    Log.d(TAG, "✅ Bluetooth device ID: " + ownDeviceId);
//                }
//            } catch (SecurityException e) {
//                Log.e(TAG, "SecurityException getting Bluetooth address", e);
//            }
//        }
//    }

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
        Log.d(TAG, "  My MAC: " + myDeviceMac);
        Log.d(TAG, "  Message destinatio n: " + message.destination_id);

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

    /**
     * Get this device's name
     * @return Device name (e.g., "Redmi Note 12 Pro 5G")
     */
    private String getMyDeviceName() {
        // Try 1: From saved preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedName = prefs.getString("MY_DEVICE_NAME", null);

        if (savedName != null && !savedName.isEmpty()) {
            Log.d(TAG, "Got device name from preferences: " + savedName);
            return savedName;
        }

        // Try 2: From Wi-Fi Direct manager
        if (manager != null && channel != null) {
            final String[] nameHolder = new String[1];
            final Object lock = new Object();

            manager.requestDeviceInfo(channel, device -> {
                synchronized (lock) {
                    if (device != null && device.deviceName != null) {
                        nameHolder[0] = device.deviceName;
                    }
                    lock.notifyAll();
                }
            });

            synchronized (lock) {
                try {
                    lock.wait(1000); // Short timeout
                    if (nameHolder[0] != null) {
                        // Save for future use
                        prefs.edit().putString("MY_DEVICE_NAME", nameHolder[0]).apply();
                        Log.d(TAG, "Got device name from manager: " + nameHolder[0]);
                        return nameHolder[0];
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Try 3: From Android Settings (fallback)
        String deviceName = Settings.Global.getString(getContentResolver(), "device_name");
        if (deviceName == null) {
            deviceName = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        if (deviceName != null) {
            Log.d(TAG, "Got device name from Settings: " + deviceName);
            prefs.edit().putString("MY_DEVICE_NAME", deviceName).apply();
            return deviceName;
        }

        Log.w(TAG, "⚠️ Could not determine device name");
        return null;
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

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION); // - ACCESS_FINE_LOCATION (for Wi-Fi Direct discovery)
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES); // - NEARBY_WIFI_DEVICES (Android 13+)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN); // - BLUETOOTH_SCAN (for Bluetooth discovery)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT); // - BLUETOOTH_CONNECT (for Bluetooth)
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]), 1);
        } else {
            discoverPeers();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "✓ Permissions granted", Toast.LENGTH_SHORT).show();
                discoverPeers();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
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

        if (bluetoothBroadcastReceiver != null && bluetoothIntentFilter != null && !isBluetoothReceiverRegistered) { // FIXED
            registerReceiver(bluetoothBroadcastReceiver, bluetoothIntentFilter); // FIXED
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

        if (itemId == R.id.menu_epidemic) {
            currentProtocol = "EPIDEMIC";
            updateProtocolDisplay();
            Toast.makeText(this, "✓ Switched to Epidemic Routing", Toast.LENGTH_SHORT).show();
        } else if (itemId == R.id.menu_spray_and_wait) {
            currentProtocol = "SPRAY_AND_WAIT";
            updateProtocolDisplay();
            Toast.makeText(this, "✓ Switched to Spray and Wait", Toast.LENGTH_SHORT).show();
        } else if (itemId == R.id.menu_transport_wifi) {
            if (isWifiDirectAvailable()) {
                activeTransport = TransportType.WIFI_DIRECT;
                updateTransportDisplay();
                Toast.makeText(this, "✓ Switched to Wi-Fi Direct", Toast.LENGTH_SHORT).show();
                discoverPeers();
            } else {
                Toast.makeText(this, "Wi-Fi Direct not available", Toast.LENGTH_SHORT).show();
            }
        } else if (itemId == R.id.menu_transport_bluetooth) {
            if (bluetoothAdapter != null) {
                activeTransport = TransportType.BLUETOOTH;
                updateTransportDisplay();
                Toast.makeText(this, "✓ Switched to Bluetooth", Toast.LENGTH_SHORT).show();
                discoverPeers();
            } else {
                Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            }
        } else if (itemId == R.id.menu_view_friends) {
            showFriendsDialog();
            return true;
        }

        editor.putString(KEY_ROUTING_PROTOCOL, currentProtocol);
        editor.apply();
        return true;
    }

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

//    @RequiresApi(api = Build.VERSION_CODES.Q)
//    public void setOwnDeviceName(String deviceName) {
//        // FIXED: Simplified - just log the name
//        Log.d(TAG, "Device name broadcast received: " + deviceName);
//
//        // Device ID will be refreshed in connectionInfoListener after connection established
//        Log.d(TAG, "Current Device ID: " + ownDeviceId);
//    }

    /**
     * FIXED: Synchronously refresh device ID after connection
     * Blocks until device info is retrieved
     */
//    public void refreshDeviceIdAfterConnection() {
//        Log.d(TAG, "=== Refreshing Device ID ===");
//
//        if (activeTransport == TransportType.WIFI_DIRECT) {
//            if (manager == null || channel == null) {
//                Log.w(TAG, "Manager or channel is null");
//                return;
//            }
//
//            // Check permissions
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
//                        != PackageManager.PERMISSION_GRANTED) {
//                    Log.w(TAG, "Missing NEARBY_WIFI_DEVICES permission");
//                    return;
//                }
//            } else {
//                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
//                        != PackageManager.PERMISSION_GRANTED) {
//                    Log.w(TAG, "Missing ACCESS_FINE_LOCATION permission");
//                    return;
//                }
//            }
//
//            try {
//                // Use a callback holder to capture the result
//                final WifiP2pDevice[] deviceHolder = new WifiP2pDevice[1];
//                final Object lock = new Object();
//
//                manager.requestDeviceInfo(channel, device -> {
//                    synchronized (lock) {
//                        deviceHolder[0] = device;
//                        lock.notifyAll(); // Wake up waiting thread
//                    }
//                });
//
//                // Wait for callback with timeout
//                synchronized (lock) {
//                    try {
//                        lock.wait(2000); // Wait max 2 seconds
//                    } catch (InterruptedException e) {
//                        Log.e(TAG, "Interrupted while waiting for device info", e);
//                        Thread.currentThread().interrupt();
//                    }
//                }
//
//                if (deviceHolder[0] != null && deviceHolder[0].deviceAddress != null) {
//                    String newDeviceId = deviceHolder[0].deviceAddress;
//                    if (!newDeviceId.equals(ownDeviceId)) {
//                        Log.d(TAG, "Device ID changed: " + ownDeviceId + " → " + newDeviceId);
//                        ownDeviceId = newDeviceId;
//                    }
//                    Log.d(TAG, "✅ Device ID confirmed (Wi-Fi Direct): " + ownDeviceId);
//                } else {
//                    Log.w(TAG, "Device info returned null");
//                }
//            } catch (SecurityException e) {
//                Log.e(TAG, "SecurityException getting device info", e);
//            }
//        } else if (activeTransport == TransportType.BLUETOOTH) {
//            if (bluetoothAdapter == null) {
//                Log.w(TAG, "Bluetooth adapter is null");
//                return;
//            }
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
//                        != PackageManager.PERMISSION_GRANTED) {
//                    Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
//                    return;
//                }
//            }
//
//            try {
//                String bluetoothId = bluetoothAdapter.getAddress();
//                if (bluetoothId != null) {
//                    ownDeviceId = bluetoothId;
//                    Log.d(TAG, "✅ Device ID set (Bluetooth): " + ownDeviceId);
//                }
//            } catch (SecurityException e) {
//                Log.e(TAG, "SecurityException getting Bluetooth address", e);
//            }
//        }
//
//        Log.d(TAG, "Final Device ID after refresh: " + ownDeviceId);
//    }



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
            if (isWifiDirectReceiverRegistered) {
                unregisterReceiver(wifiDirectBroadcastReceiver);
                isWifiDirectReceiverRegistered = false;
            }
            if (isBluetoothReceiverRegistered) {
                unregisterReceiver(bluetoothBroadcastReceiver);
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

    /**
     * Initialize device ID synchronously BEFORE connections
     * Gets the actual MAC address for the active transport
     */
    private void initializeDeviceIdSync() {
        Log.d(TAG, "Initializing device ID for transport: " + activeTransport);

        if (activeTransport == TransportType.WIFI_DIRECT) {
            if (manager != null && channel != null) {
                try {
                    // For Wi-Fi Direct, request device info
                    final Object lock = new Object();
                    final WifiP2pDevice[] deviceHolder = new WifiP2pDevice[1];

                    manager.requestDeviceInfo(channel, device -> {
                        synchronized (lock) {
                            deviceHolder[0] = device;
                            lock.notifyAll();
                        }
                    });

                    // Wait for result with timeout
                    synchronized (lock) {
                        try {
                            lock.wait(2000); // Wait max 2 seconds
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    if (deviceHolder[0] != null && deviceHolder[0].deviceAddress != null) {
                        ownDeviceId = deviceHolder[0].deviceAddress;
                        Log.d(TAG, "✅ Device ID initialized (Wi-Fi Direct): " + ownDeviceId);
                    } else {
                        Log.w(TAG, "⚠️ Could not get Wi-Fi Direct device info, using temporary ID");
                        ownDeviceId = UUID.randomUUID().toString();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing Wi-Fi Direct device ID", e);
                    ownDeviceId = UUID.randomUUID().toString();
                }
            } else {
                Log.w(TAG, "Manager or channel is null");
                ownDeviceId = UUID.randomUUID().toString();
            }
        } else if (activeTransport == TransportType.BLUETOOTH) {
            if (bluetoothAdapter != null) {
                try {
                    ownDeviceId = bluetoothAdapter.getAddress();
                    if (ownDeviceId != null && !ownDeviceId.isEmpty()) {
                        Log.d(TAG, "✅ Device ID initialized (Bluetooth): " + ownDeviceId);
                    } else {
                        Log.w(TAG, "Bluetooth address is empty, using temporary ID");
                        ownDeviceId = UUID.randomUUID().toString();
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException getting Bluetooth address", e);
                    ownDeviceId = UUID.randomUUID().toString();
                } catch (Exception e) {
                    Log.e(TAG, "Error getting Bluetooth address", e);
                    ownDeviceId = UUID.randomUUID().toString();
                }
            } else {
                Log.w(TAG, "Bluetooth adapter not available");
                ownDeviceId = UUID.randomUUID().toString();
            }
        } else {
            Log.w(TAG, "Unknown transport type");
            ownDeviceId = UUID.randomUUID().toString();
        }

        Log.d(TAG, "Final ownDeviceId: " + ownDeviceId);
    }

}
