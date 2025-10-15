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

    // UI Elements
    public TextView statusTextView;
    ListView peerListView, chatListView;
    EditText messageEditText;
    Button sendButton;
    Spinner prioritySpinner;

    // Wi-Fi Direct
    WifiP2pManager manager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver receiver;
    IntentFilter intentFilter;
    List<WifiP2pDevice> peers = new ArrayList<>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;

    // Networking Threads
    ServerThread serverThread;
    ClientThread clientThread;
    Handler handler;

    // Database
    AppDatabase database;
    MessageDao messageDao;
    ExecutorService executor = Executors.newSingleThreadExecutor(); // For DB operations

    // Chat data
    ArrayList<String> chatMessages = new ArrayList<>();
    ArrayAdapter<String> chatAdapter;

    //logger
    Logger logger;
    RoutingProtocol activeRoutingProtocol;

    private String ownDeviceId = ""; // To store our own device name/ID

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logger = Logger.getInstance(getApplicationContext());

        initializeUI();
        initializeWifiDirect();
        initializeDatabase();
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
        // This handler receives messages from the background networking threads
        handler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == ServerThread.MESSAGE_READ) {
                Message receivedMessage = (Message) msg.obj;
                handleReceivedMessage(receivedMessage);
            }
            return true;
        });
    }

    private void setListeners() {
        // Listener for when a peer is tapped in the list
        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            final WifiP2pDevice device = deviceArray[position];
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
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

        // Listener for the send button
        sendButton.setOnClickListener(v -> {
            String msgText = messageEditText.getText().toString();
            if (msgText.isEmpty()) return;

            executor.execute(() -> {
                try {
                    Message message = new Message();
                    message.source_id = ownDeviceId;
                    message.destination_id = "BROADCAST"; // For now, all messages are broadcast
                    message.encrypted_payload = CryptoUtils.encrypt(msgText);
                    message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);
                    message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH") ? 1 : 0;
                    message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2-hour TTL
                    message.hop_count = 0;
                    message.copy_count = 8; // Default for Spray & Wait

                    // Save message to own database
                    messageDao.insert(message);

                    // Log the creation event
                    String logDetails = String.format(Locale.US,
                            "EVENT=MESSAGE_CREATED | MSG_ID=%s | FROM=%s | TO=%s | PRIORITY=%d",
                            message.message_id, message.source_id, message.destination_id, message.priority);
                    logger.logEvent(logDetails);


                    // Send message over the network
                    if (serverThread != null) serverThread.write(message);
                    if (clientThread != null) clientThread.write(message);

                    runOnUiThread(() -> {
                        chatMessages.add("Me: " + msgText);
                        chatAdapter.notifyDataSetChanged();
                        messageEditText.setText("");
                    });

                } catch (Exception e) {
                    Log.e("SendClick", "Error creating/sending message", e);
                }
            });
        });
    }

    // Callback for when peer list changes
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

    // Callback for when a connection is established
    public WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
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

            // Once connected, initialize the routing protocol and trigger forwarding
            // TODO: In the next step, we will make this switchable
            activeRoutingProtocol = new EpidemicRouting(getApplicationContext(), ownDeviceId);

            // Use the executor to perform DB and network operations off the main thread
            executor.execute(() -> {
                List<Message> allMessages = messageDao.getAllMessages();
                activeRoutingProtocol.forwardMessages(allMessages, serverThread, clientThread);
            });
        }
    };

    private void handleReceivedMessage(Message message) {
        executor.execute(() -> {
            try {
                // First, validate the message checksum
                boolean isValid = CryptoUtils.validateChecksum(message.encrypted_payload, message.checksum);
                if (!isValid) {
                    Log.w("Receiver", "Invalid checksum. Message discarded.");
                    return; // Discard message
                }

                // Check if we already have this message to prevent loops
                Message existing = messageDao.getMessageById(message.message_id);
                if (existing != null) {
                    return; // Already processed this message
                }

                // Decrypt and display
                String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
                runOnUiThread(() -> {
                    chatMessages.add(message.source_id + ": " + decryptedText);
                    chatAdapter.notifyDataSetChanged();
                });

                // Save the new, valid message to our database (Store)
                messageDao.insert(message);

                // The received message is now part of our store, so we re-evaluate forwarding for all messages
                List<Message> allMessages = messageDao.getAllMessages();
                if (activeRoutingProtocol != null) {
                    activeRoutingProtocol.forwardMessages(allMessages, serverThread, clientThread);
                }

            } catch (Exception e) {
                Log.e("Receiver", "Error handling received message", e);
            }
        });
    }

    public void setOwnDeviceName(String name) {
        this.ownDeviceId = name;
        Log.d("MainActivity", "This device name is set to: " + ownDeviceId);
    }

    // --- Discovery and Permissions --- (Mostly unchanged)
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
}