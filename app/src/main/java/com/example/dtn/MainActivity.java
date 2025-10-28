package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // --- Database & Data ---
    AppDatabase database;
    MessageDao messageDao;
    FriendDao friendDao;

    // --- UI Elements ---
    public TextView statusTextView;
    public TextView protocolTextView;
    ListView peerListView, chatListView;
    EditText messageEditText;
    Button sendButton;
    Spinner prioritySpinner;
    Toolbar toolbar;

    // --- Wi-Fi Direct ---
    WifiP2pManager manager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver receiver;
    IntentFilter intentFilter;
    List<WifiP2pDevice> peers = new ArrayList<>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;
    private String ownDeviceId = "";
    private boolean isReceiverRegistered = false;

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
    public static final String PREFS_NAME = "DTNPrefs";
    public static final String KEY_ROUTING_PROTOCOL = "RoutingProtocol";
    private String currentProtocol = "EPIDEMIC";

    public static class ChatMessage {
        String text;
        String messageId; // Track message ID for updates
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load saved protocol preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentProtocol = prefs.getString(KEY_ROUTING_PROTOCOL, "EPIDEMIC");

        initializeUI();
        updateProtocolDisplay();
        setupPrioritySpinner();
        initializeWifiDirect();
        initializeDatabase();
        logger = Logger.getInstance(getApplicationContext());
        initializeHandler();
        setListeners();
        requestPermissions();

        // Load messages after database is initialized
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            loadMessagesFromDatabase();
        }, 1000);
    }

    private void initializeUI() {
        statusTextView = findViewById(R.id.statusTextView);
        protocolTextView = findViewById(R.id.protocolTextView);
        peerListView = findViewById(R.id.peerListView);
        chatListView = findViewById(R.id.chatListView);
        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);
        prioritySpinner = findViewById(R.id.prioritySpinner);
        toolbar = findViewById(R.id.toolbar);

        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);

        statusTextView.setText("Status: Disconnected");
        statusTextView.setTextColor(0xFFF44336); // Red
    }

    private void updateProtocolDisplay() {
        if (currentProtocol.equals("SPRAY_AND_WAIT")) {
            protocolTextView.setText("Protocol: Spray and Wait 🔵");
            protocolTextView.setTextColor(0xFF1976D2); // Blue
        } else {
            protocolTextView.setText("Protocol: Epidemic Routing 🟠");
            protocolTextView.setTextColor(0xFFFF6F00); // Orange
        }
    }

    private void setupPrioritySpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.priority_options,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prioritySpinner.setAdapter(adapter);
        prioritySpinner.setSelection(0); // Default to NORMAL
    }

    private void initializeWifiDirect() {
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Wi-Fi Direct not supported", Toast.LENGTH_LONG).show();
            Log.e(TAG, "WifiP2pManager is null");
            return;
        }

        channel = manager.initialize(this, getMainLooper(), null);
        if (channel == null) {
            Toast.makeText(this, "Failed to initialize Wi-Fi Direct", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Channel is null");
            return;
        }

        receiver = new WifiDirectBroadcastReceiver(manager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void initializeDatabase() {
        database = AppDatabase.getDatabase(getApplicationContext());
        messageDao = database.messageDao();
        friendDao = database.friendDao();
    }

    private void initializeHandler() {
        handler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == ServerThread.MESSAGE_READ) {
                Message receivedMessage = (Message) msg.obj;
                Log.d(TAG, "Handler: Received message ID: " + receivedMessage.message_id);
                handleReceivedMessage(receivedMessage);
            }
            return true;
        });
    }

    private void setListeners() {
        peerListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (deviceArray == null || position >= deviceArray.length) {
                return false;
            }
            final WifiP2pDevice device = deviceArray[position];
            new AlertDialog.Builder(this)
                    .setTitle("Add Friend")
                    .setMessage("Do you want to add " + device.deviceName + " as a friend?")
                    .setPositiveButton("Yes", (dialog, which) -> addFriend(device))
                    .setNegativeButton("No", null)
                    .show();
            return true;
        });

        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            if (deviceArray == null || position >= deviceArray.length) {
                Toast.makeText(this, "Invalid peer selection", Toast.LENGTH_SHORT).show();
                return;
            }

            final WifiP2pDevice device = deviceArray[position];
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;

            // Check permissions based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "NEARBY_WIFI_DEVICES permission required", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            Log.d(TAG, "Connecting to: " + device.deviceName);
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Toast.makeText(getApplicationContext(), "Connecting to " + device.deviceName, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Connection initiated");
                }
                @Override
                public void onFailure(int reason) {
                    String failureReason = getConnectionFailureReason(reason);
                    Toast.makeText(getApplicationContext(), "Connection failed: " + failureReason, Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Connection failed: " + failureReason);
                }
            });
        });

        sendButton.setOnClickListener(v -> {
            String msgText = messageEditText.getText().toString().trim();
            if (msgText.isEmpty()) {
                Toast.makeText(this, "Message is empty", Toast.LENGTH_SHORT).show();
                return;
            }

            // NEW: Let user select destination from friends list
            executor.execute(() -> {
                List<Friend> friends = friendDao.getAllFriends();

                runOnUiThread(() -> {
                    if (friends.isEmpty()) {
                        Toast.makeText(this, "Add friends first (long-press on peer)", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Create friend selection dialog
                    String[] friendNames = new String[friends.size()];
                    String[] friendIds = new String[friends.size()];

                    for (int i = 0; i < friends.size(); i++) {
                        friendNames[i] = friends.get(i).friendlyName;
                        friendIds[i] = friends.get(i).deviceId;
                    }

                    new AlertDialog.Builder(this)
                            .setTitle("Send Message To:")
                            .setItems(friendNames, (dialog, which) -> {
                                String selectedFriendId = friendIds[which];
                                String selectedFriendName = friendNames[which];

                                // Send message to selected friend (even if offline)
                                sendMessageToDestination(msgText, selectedFriendId, selectedFriendName);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            });
        });

    }

    private String getConnectionFailureReason(int reason) {
        switch (reason) {
            case WifiP2pManager.ERROR: return "Internal error";
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P unsupported";
            case WifiP2pManager.BUSY: return "Busy";
            default: return "Unknown (" + reason + ")";
        }
    }

    private void addFriend(WifiP2pDevice device) {
        executor.execute(() -> {
            try {
                Friend existingFriend = friendDao.getFriendById(device.deviceAddress);
                if (existingFriend == null) {
                    Friend newFriend = new Friend();
                    newFriend.deviceId = device.deviceAddress;
                    newFriend.friendlyName = device.deviceName;
                    newFriend.lastEncounteredTimestamp = 0;
                    friendDao.insert(newFriend);
                    runOnUiThread(() -> Toast.makeText(this, device.deviceName +
                            " added as friend", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(this, device.deviceName +
                            " already a friend", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error adding friend", e);
            }
        });
    }

    public WifiP2pManager.PeerListListener peerListListener = peerList -> {
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

            Log.d(TAG, "Discovered " + peers.size() + " peer(s)");
        }
    };

    public WifiP2pManager.ConnectionInfoListener connectionInfoListener = info -> {
        Log.d(TAG, "Connection info received - Group formed: " + info.groupFormed);

        final InetAddress groupOwnerAddress = info.groupOwnerAddress;

        if (info.groupFormed && info.isGroupOwner) {
            if (serverThread == null || !serverThread.isAlive()) {
                Log.d(TAG, "Starting ServerThread");
                statusTextView.setText("Status: Connected as Host");
                statusTextView.setTextColor(0xFF4CAF50); // Green
                serverThread = new ServerThread(handler);
                serverThread.start();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    WifiP2pDevice connectedPeer = findConnectedPeer();
                    if (connectedPeer != null) {
                        triggerForwardingLogic(connectedPeer);
                    }
                }, 2000);
            }
        } else if (info.groupFormed) {
            if (clientThread == null || !clientThread.isAlive()) {
                Log.d(TAG, "Starting ClientThread");
                statusTextView.setText("Status: Connected as Client");
                statusTextView.setTextColor(0xFF4CAF50); // Green
                clientThread = new ClientThread(groupOwnerAddress, handler);
                clientThread.start();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    WifiP2pDevice connectedPeer = findConnectedPeer();
                    if (connectedPeer != null) {
                        triggerForwardingLogic(connectedPeer);
                    }
                }, 2000);
            }
        }
    };

    public void onDisconnect() {
        Log.d(TAG, "Disconnected");
        statusTextView.setText("Status: Disconnected");
        statusTextView.setTextColor(0xFFF44336); // Red

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
                Friend connectedFriend = friendDao.getFriendById(peer.deviceAddress);
                if (connectedFriend != null) {
                    connectedFriend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(connectedFriend);
                }

                List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                List<Message> messagesToForward = new ArrayList<>();

                for (Message message : allMessages) {
                    if (peer.deviceAddress.equals(message.destination_id)) {
                        messagesToForward.add(message);
                        continue;
                    }

                    Friend destinationFriend = friendDao.getFriendById(message.destination_id);
                    if (destinationFriend != null && connectedFriend != null) {
                        if (connectedFriend.lastEncounteredTimestamp > destinationFriend.lastEncounteredTimestamp) {
                            messagesToForward.add(message);
                        }
                    }
                }

                Collections.sort(messagesToForward, (m1, m2) -> {
                    int priorityCompare = Integer.compare(m2.priority, m1.priority);
                    return priorityCompare != 0 ? priorityCompare : Long.compare(m2.ttl_timestamp, m1.ttl_timestamp);
                });

                if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                    activeRoutingProtocol = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao);
                } else {
                    activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId);
                }

                if (activeRoutingProtocol != null && !messagesToForward.isEmpty()) {
                    activeRoutingProtocol.forwardMessages(messagesToForward, peer, serverThread, clientThread);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in forwarding logic", e);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void setOwnDeviceName(String deviceName) {
        Log.d(TAG, "Setting device name: " + deviceName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        if (manager == null || channel == null) return;

        manager.requestDeviceInfo(channel, device -> {
            if (device != null) {
                this.ownDeviceId = device.deviceAddress;
                Log.d(TAG, "Device ID: " + this.ownDeviceId);
            }
        });
    }

    private void handleReceivedMessage(Message message) {
        executor.execute(() -> {
            try {
                if (!CryptoUtils.validateChecksum(message.encrypted_payload, message.checksum)) {
                    logger.logEvent("EVENT=MESSAGE_DROPPED_INVALID_CHECKSUM | MSG_ID=" + message.message_id);
                    return;
                }

                if (message.message_type == Message.TYPE_ACK) {
                    processAck(message);
                } else {
                    processDataMessage(message);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling message", e);
            }
        });
    }

    private void processDataMessage(Message message) throws Exception {
        Message existing = messageDao.getMessageById(message.message_id);
        if (existing != null) {
            Log.d(TAG, "Duplicate message received, ignoring");
            return;
        }

        // Always store the message (for forwarding)
        messageDao.insert(message);
        Log.d(TAG, "Message stored in database: " + message.message_id);

        // Log destination check
        Log.d(TAG, "ownDeviceId: " + ownDeviceId);
        Log.d(TAG, "message.destination_id: " + message.destination_id);
        Log.d(TAG, "message.source_id: " + message.source_id);

        // ONLY show in UI if message is FOR ME
        if (ownDeviceId.equals(message.destination_id)) {
            String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
            Log.d(TAG, "✓ Message is for me! Decrypted: " + decryptedText);

            // Show in UI
            runOnUiThread(() -> {
                String displayText = "From Peer: " + decryptedText;
                Log.d(TAG, "Adding received message to UI: " + displayText);

                ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, true);
                chatMessages.add(chatMessage);
                chatAdapter.notifyDataSetChanged();
                chatListView.smoothScrollToPosition(chatMessages.size() - 1);

                Log.d(TAG, "Message displayed in UI, total messages: " + chatMessages.size());
                Toast.makeText(this, "📩 New message received!", Toast.LENGTH_SHORT).show();
            });

            logger.logEvent("EVENT=MESSAGE_DELIVERED | MSG_ID=" + message.message_id);

            // Generate ACK back to sender
            generateAndSendAck(message);
        } else {
            // Message is NOT for me - just forward it
            Log.d(TAG, "Message not for me (dest: " + message.destination_id + "), will forward to correct destination");
            logger.logEvent("EVENT=MESSAGE_FORWARDED_TRANSIT | MSG_ID=" + message.message_id +
                    " | DEST=" + message.destination_id);
        }

        // Trigger forwarding logic for ALL messages (whether for me or not)
        WifiP2pDevice connectedPeer = findConnectedPeer();
        if (connectedPeer != null) {
            triggerForwardingLogic(connectedPeer);
        }
    }

    private void generateAndSendAck(Message originalMessage) throws Exception {
        Message ackMessage = new Message();
        ackMessage.message_type = Message.TYPE_ACK;
        ackMessage.destination_id = originalMessage.source_id;
        ackMessage.source_id = ownDeviceId;
        ackMessage.encrypted_payload = CryptoUtils.encrypt(originalMessage.message_id);
        ackMessage.checksum = CryptoUtils.generateChecksum(ackMessage.encrypted_payload);
        ackMessage.priority = 1;
        ackMessage.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000);
        ackMessage.hop_count = 0;
        ackMessage.copy_count = 1;

        messageDao.insert(ackMessage);
        logger.logEvent("EVENT=ACK_GENERATED | FOR_MSG_ID=" + originalMessage.message_id);
        Log.d(TAG, "ACK generated for message: " + originalMessage.message_id);

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.write(ackMessage);
            Log.d(TAG, "ACK sent via ServerThread");
        } else if (clientThread != null && clientThread.isAlive()) {
            clientThread.write(ackMessage);
            Log.d(TAG, "ACK sent via ClientThread");
        }
    }

    private void processAck(Message ackMessage) throws Exception {
        Log.d(TAG, "Processing ACK message");

        if (!ownDeviceId.equals(ackMessage.destination_id)) {
            // Forward ACK if not for us
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

        // ACK is for us
        String originalMessageId = CryptoUtils.decrypt(ackMessage.encrypted_payload);
        Log.d(TAG, "ACK received for original message: " + originalMessageId);

        Message messageToUpdate = messageDao.getMessageById(originalMessageId);

        if (messageToUpdate != null && !messageToUpdate.is_delivered) {
            messageToUpdate.is_delivered = true;
            messageDao.update(messageToUpdate);
            logger.logEvent("EVENT=MESSAGE_DELIVERED_ACK_RECEIVED | MSG_ID=" + originalMessageId);
            Log.d(TAG, "Message marked as delivered in database");

            // Update UI
            runOnUiThread(() -> {
                for (ChatMessage cm : chatMessages) {
                    if (cm.messageId.equals(originalMessageId)) {
                        cm.isDelivered = true;
                        chatAdapter.notifyDataSetChanged();
                        Toast.makeText(this, "✓ Message Delivered!", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "UI updated - message marked as delivered");
                        break;
                    }
                }
            });
        }
    }

    private void discoverPeers() {
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

        Log.d(TAG, "Starting peer discovery");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discovery started");
                statusTextView.setText("Status: Discovering...");
                statusTextView.setTextColor(0xFFFFC107); // Yellow
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Discovery failed: " + reason);
                statusTextView.setText("Status: Discovery Failed");
            }
        });
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
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
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
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
        if (receiver != null && intentFilter != null && !isReceiverRegistered) {
            registerReceiver(receiver, intentFilter);
            isReceiverRegistered = true;
            Log.d(TAG, "Receiver registered");

            discoverPeers();

        }
        // Reload messages when app comes back to foreground
        loadMessagesFromDatabase();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause - keeping receiver registered");
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

        } else if (itemId == R.id.menu_view_friends) {
            showFriendsDialog();
            return true;

        } else if (itemId == R.id.menu_clear_messages) {
            clearMessagesDialog();
            return true;

        } else if (itemId == R.id.menu_about) {
            showAboutDialog();
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

    private void clearMessagesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Messages")
                .setMessage("Clear all chat messages?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    chatMessages.clear();
                    chatAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Messages cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAboutDialog() {
        String aboutText = "DTN Messenger v1.0\n\n" +
                "Delay-Tolerant Network Messenger\n\n" +
                "Features:\n" +
                "• Epidemic Routing\n" +
                "• Spray-and-Wait Routing\n" +
                "• Wi-Fi Direct P2P\n" +
                "• AES Encryption\n" +
                "• Message Acknowledgements";

        new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage(aboutText)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");

        if (receiver != null && isReceiverRegistered) {
            try {
                unregisterReceiver(receiver);
                isReceiverRegistered = false;
                Log.d(TAG, "Receiver unregistered in onDestroy");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver not registered", e);
            }
        }

        if (serverThread != null) {
            serverThread.close();
        }
        if (clientThread != null) {
            clientThread.close();
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }

        if (logger != null) {
            logger.shutdown();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && channel != null) {
            channel.close();
        }
    }

    private void loadMessagesFromDatabase() {
        executor.execute(() -> {
            try {
                // Get all non-expired messages from database
                List<Message> allMessages = messageDao.getNonExpiredMessages(System.currentTimeMillis());
                Log.d(TAG, "Found " + allMessages.size() + " messages in database");

                runOnUiThread(() -> {
                    chatMessages.clear();

                    for (Message msg : allMessages) {
                        // ONLY show messages that are:
                        // 1. Sent BY me (source = ownDeviceId)
                        // 2. Sent TO me (destination = ownDeviceId)

                        boolean isFromMe = msg.source_id.equals(ownDeviceId);
                        boolean isForMe = msg.destination_id.equals(ownDeviceId);

                        // Skip transit messages (messages I'm just forwarding)
                        if (!isFromMe && !isForMe) {
                            Log.d(TAG, "Skipping transit message: " + msg.message_id);
                            continue;
                        }

                        try {
                            // Decrypt message
                            String decryptedText = CryptoUtils.decrypt(msg.encrypted_payload);

                            // Format display text
                            String displayText;
                            if (isFromMe) {
                                // Message sent by me
                                displayText = "Me: " + decryptedText;
                            } else {
                                // Message received by me
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

                    Log.d(TAG, "✓ Loaded " + chatMessages.size() + " messages for this device");
                    if (chatMessages.size() > 0) {
                        Toast.makeText(this, "Loaded " + chatMessages.size() + " messages",
                                Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading messages from database", e);
            }
        });
    }


    private void sendMessageToDestination(String msgText, String destId, String destName) {
        final Message message = new Message();

        String displayText = String.format(Locale.US, "To %s: %s", destName, msgText);
        Log.d(TAG, "Adding message to UI: " + displayText);

        ChatMessage chatMessage = new ChatMessage(displayText, message.message_id, false);
        chatMessages.add(chatMessage);
        chatAdapter.notifyDataSetChanged();
        chatListView.smoothScrollToPosition(chatMessages.size() - 1);
        messageEditText.setText("");

        executor.execute(() -> {
            try {
                message.source_id = ownDeviceId;
                message.destination_id = destId;  // Can be offline device!
                message.encrypted_payload = CryptoUtils.encrypt(msgText);
                message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);
                message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH") ? 1 : 0;
                message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000);
                message.hop_count = 0;
                message.copy_count = currentProtocol.equals("SPRAY_AND_WAIT") ?
                        SprayAndWaitRouting.INITIAL_COPIES : 1;

                messageDao.insert(message);
                Log.d(TAG, "Message stored for offline delivery to: " + destName);

                // Try to send now if connected to someone
                if (serverThread != null && serverThread.isAlive()) {
                    serverThread.write(message);
                    Log.d(TAG, "Message forwarded immediately via ServerThread");
                } else if (clientThread != null && clientThread.isAlive()) {
                    clientThread.write(message);
                    Log.d(TAG, "Message forwarded immediately via ClientThread");
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "📦 Message queued for delivery when peer is in range",
                                Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
            }
        });
    }

}
