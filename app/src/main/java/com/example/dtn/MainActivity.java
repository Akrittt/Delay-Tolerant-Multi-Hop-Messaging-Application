package com.example.dtn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import java.util.Collections;
import java.util.Comparator;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.dtn.data.AppDatabase;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@SuppressLint("SetTextI18n")
public class MainActivity extends AppCompatActivity {

    // --- UI Elements ---
    public TextView statusTextView;
    ListView peerListView, chatListView;
    EditText messageEditText;
    Button sendButton;
    Spinner prioritySpinner;

    // --- Wi-Fi Direct ---
    WifiP2pManager manager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver receiver;
    IntentFilter intentFilter;
    List<WifiP2pDevice> peers = new ArrayList<>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;
    private String ownDeviceId = "";

    // --- Networking & Threads ---
    ServerThread serverThread;
    ClientThread clientThread;
    Handler handler;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    // --- Database & Data ---
    AppDatabase database;
    MessageDao messageDao;
    ArrayList<ChatMessage> chatMessages = new ArrayList<>();
    ArrayAdapter<ChatMessage> chatAdapter;

    // --- Logging & Routing ---
    Logger logger;
    RoutingProtocol activeRoutingProtocol;
    public static final String PREFS_NAME = "DTNPrefs";
    public static final String KEY_ROUTING_PROTOCOL = "RoutingProtocol";
    private String currentProtocol = "EPIDEMIC"; // Default protocol

    /**
     * Inner class to manage the UI representation of a chat message,
     * including its text and delivery status.
     */
    public static class ChatMessage {
        String text;
        boolean isDelivered;

        ChatMessage(String text, boolean isDelivered) {
            this.text = text;
            this.isDelivered = isDelivered;
        }

        @NonNull
        @Override
        public String toString() {
            // Display ✓ for delivered, ... for sending
            return text + (isDelivered ? " ✓" : " ...");
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentProtocol = prefs.getString(KEY_ROUTING_PROTOCOL, "EPIDEMIC");
        initializeUI();
        initializeWifiDirect();
        initializeDatabase();
        logger = Logger.getInstance(getApplicationContext());
        initializeHandler();
        setListeners();
        requestPermissions();
    }

    private void initializeUI() {
        statusTextView = findViewById(R.id.statusTextView);
        peerListView = findViewById(R.id.peerListView);
        chatListView = findViewById(R.id.chatListView);
        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);
        prioritySpinner = findViewById(R.id.prioritySpinner);

        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);
    }

    private void initializeWifiDirect() {
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
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
    }

    private void initializeHandler() {
        handler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == ServerThread.MESSAGE_READ) {
                Message receivedMessage = (Message) msg.obj;
                handleReceivedMessage(receivedMessage);
            }
            return true;
        });
    }

    private void setListeners() {
        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            final WifiP2pDevice device = deviceArray[position];
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission check failed before connect", Toast.LENGTH_SHORT).show();
                return;
            }
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Toast.makeText(getApplicationContext(), "Connecting to " + device.deviceName, Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onFailure(int reason) {
                    Toast.makeText(getApplicationContext(), "Connection failed. Try again.", Toast.LENGTH_SHORT).show();
                }
            });
        });

        sendButton.setOnClickListener(v -> {
            String msgText = messageEditText.getText().toString();
            if (msgText.isEmpty() || deviceArray == null || deviceArray.length == 0) {
                Toast.makeText(this, "No destination peer available", Toast.LENGTH_SHORT).show();
                return;
            }
            final String destinationId = deviceArray[0].deviceName; // Send to first peer for simplicity
            final Message message = new Message();

            runOnUiThread(() -> {
                String displayText = String.format(Locale.US, "Me (%s): %s", message.message_id.substring(0, 8), msgText);
                ChatMessage chatMessage = new ChatMessage(displayText, false);
                chatMessages.add(chatMessage);
                chatAdapter.notifyDataSetChanged();
                messageEditText.setText("");
            });

            executor.execute(() -> {
                try {
                    message.source_id = ownDeviceId;
                    message.destination_id = destinationId;
                    message.encrypted_payload = CryptoUtils.encrypt(msgText);
                    message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);
                    message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH") ? 1 : 0;
                    message.copy_count = currentProtocol.equals("SPRAY_AND_WAIT") ? SprayAndWaitRouting.INITIAL_COPIES : 1;
                    message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2-hour TTL
                    message.hop_count = 0;
                    message.copy_count = 8; // Default for Spray & Wait

                    messageDao.insert(message);

                    String logDetails = String.format(Locale.US,
                            "EVENT=MESSAGE_CREATED | MSG_ID=%s | FROM=%s | TO=%s | PRIORITY=%d",
                            message.message_id, message.source_id, message.destination_id, message.priority);
                    logger.logEvent(logDetails);

                    if (serverThread != null) serverThread.write(message);
                    if (clientThread != null) clientThread.write(message);
                } catch (Exception e) {
                    Log.e("SendClick", "Error creating/sending message", e);
                }
            });
        });
    }

    // --- Wi-Fi Direct Callbacks ---

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
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_list_item_1, deviceNameArray);
            peerListView.setAdapter(adapter);
        }
        if (peers.isEmpty()) {
            Toast.makeText(getApplicationContext(), "No Devices Found", Toast.LENGTH_SHORT).show();
        }
    };

    public WifiP2pManager.ConnectionInfoListener connectionInfoListener = info -> {
        final InetAddress groupOwnerAddress = info.groupOwnerAddress;
        if (info.groupFormed && info.isGroupOwner) {
            statusTextView.setText("Status: Connected as Host");
            serverThread = new ServerThread(handler);
            serverThread.start();
        } else if (info.groupFormed) {
            statusTextView.setText("Status: Connected as Client");
            clientThread = new ClientThread(groupOwnerAddress, handler);
            clientThread.start();
        }
        // When connected, trigger the forwarding logic
        WifiP2pDevice connectedPeer = findConnectedPeer();
        if(connectedPeer != null) {
            triggerForwardingLogic(connectedPeer);
        }
    };
    private WifiP2pDevice findConnectedPeer() {
        for(WifiP2pDevice peer : peers) {
            if(peer.status == WifiP2pDevice.CONNECTED) {
                return peer;
            }
        }
        return null;
    }
    private void triggerForwardingLogic(final WifiP2pDevice peer) {
        executor.execute(() -> {
            // 1. Get all messages from the database
            List<Message> allMessages = messageDao.getAllMessages();
            List<Message> messagesToForward = new ArrayList<>();

            // 2. Filter out expired messages (TTL Check)
            long currentTime = System.currentTimeMillis();
            for (Message msg : allMessages) {
                if (msg.ttl_timestamp > currentTime) {
                    messagesToForward.add(msg);
                } else {
                    logger.logEvent("EVENT=MESSAGE_DROPPED_TTL | MSG_ID=" + msg.message_id);
                }
            }

            // 3. Sort for congestion control (Priority & then by newest)
            Collections.sort(messagesToForward, (m1, m2) -> {
                int priorityCompare = Integer.compare(m2.priority, m1.priority); // High priority first
                if (priorityCompare == 0) {
                    return Long.compare(m2.ttl_timestamp, m1.ttl_timestamp); // Freshest TTL first
                }
                return priorityCompare;
            });

            // 4. Initialize the correct routing protocol
            if (currentProtocol.equals("SPRAY_AND_WAIT")) {
                activeRoutingProtocol = new SprayAndWaitRouting(getApplicationContext(), ownDeviceId, messageDao, executor);
            } else {
                activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId);
            }

            // 5. Execute forwarding
            if (activeRoutingProtocol != null) {
                activeRoutingProtocol.forwardMessages(messagesToForward, peer, serverThread, clientThread);
            }
        });
    }

    public void setOwnDeviceName(String name) {
        this.ownDeviceId = name;
        Log.d("MainActivity", "This device name is set to: " + ownDeviceId);
    }

    // --- Message Handling Logic ---

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
                Log.e("Receiver", "Error handling message", e);
            }
        });
    }

    private void processDataMessage(Message message) throws Exception {
        Message existing = messageDao.getMessageById(message.message_id);
        if (existing != null) return; // Duplicate

        messageDao.insert(message);

        String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
        runOnUiThread(() -> {
            chatMessages.add(new ChatMessage(message.source_id + ": " + decryptedText, true)); // True because it's delivered to us
            chatAdapter.notifyDataSetChanged();
        });

        if (ownDeviceId.equals(message.destination_id)) {
            logger.logEvent("EVENT=MESSAGE_DELIVERED | MSG_ID=" + message.message_id);
            generateAndSendAck(message);
        }

        // Forward the message to other peers based on the routing protocol
        WifiP2pDevice connectedPeer = findConnectedPeer();
        if(connectedPeer != null) {
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

        messageDao.insert(ackMessage);
        logger.logEvent("EVENT=ACK_GENERATED | FOR_MSG_ID=" + originalMessage.message_id);

        if (serverThread != null) serverThread.write(ackMessage);
        if (clientThread != null) clientThread.write(ackMessage);
    }

    private void processAck(Message ackMessage) throws Exception {
        if (!ownDeviceId.equals(ackMessage.destination_id)) {
            // Not for me, but I should store and forward it
            Message existing = messageDao.getMessageById(ackMessage.message_id);
            if (existing == null) {
                // This is a new ACK for someone else. Store it in our database.
                messageDao.insert(ackMessage);

                // Trigger the forwarding logic to pass this ACK along to the next peer.
                WifiP2pDevice connectedPeer = findConnectedPeer();
                if (connectedPeer != null) {
                    triggerForwardingLogic(connectedPeer);
                }
            }
            return;
        }

        // This ACK is for me.
        String originalMessageId = CryptoUtils.decrypt(ackMessage.encrypted_payload);
        Message messageToUpdate = messageDao.getMessageById(originalMessageId);

        if (messageToUpdate != null && !messageToUpdate.is_delivered) {
            messageToUpdate.is_delivered = true;
            messageDao.update(messageToUpdate);
            logger.logEvent("EVENT=MESSAGE_DELIVERED_ACK_RECEIVED | MSG_ID=" + originalMessageId);

            runOnUiThread(() -> {
                for (ChatMessage cm : chatMessages) {
                    if (cm.text.contains(originalMessageId.substring(0, 8))) {
                        cm.isDelivered = true;
                        break;
                    }
                }
                chatAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Delivery Confirmed!", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // --- Permissions and Lifecycle ---

    private void discoverPeers() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissions required for discovery were not granted.", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                statusTextView.setText("Discovery Started");
            }
            @Override
            public void onFailure(int reason) {
                statusTextView.setText("Discovery Failed. Reason Code: " + reason);
            }
        });
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), 1);
        } else {
            discoverPeers();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions granted. Starting discovery.", Toast.LENGTH_SHORT).show();
                discoverPeers();
            } else {
                Toast.makeText(this, "Location permissions are required for peer discovery.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
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
            Toast.makeText(this, "Switched to Epidemic Routing", Toast.LENGTH_SHORT).show();
        } else if (itemId == R.id.menu_spray_and_wait) {
            currentProtocol = "SPRAY_AND_WAIT";
            Toast.makeText(this, "Switched to Spray and Wait", Toast.LENGTH_SHORT).show();
        }
        editor.putString(KEY_ROUTING_PROTOCOL, currentProtocol);
        editor.apply();
        return true;
    }
}
