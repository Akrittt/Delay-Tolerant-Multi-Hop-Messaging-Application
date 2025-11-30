package com.example.dtn.view;

import android.annotation.SuppressLint;
import android.os.Bundle;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.example.dtn.R;
import com.example.dtn.model.data.Friend;
import com.example.dtn.model.data.Message;
import com.example.dtn.managers.BluetoothManager;
import com.example.dtn.managers.ConnectionManager;
import com.example.dtn.managers.PermissionManager;
import com.example.dtn.managers.WifiDirectManager;
import com.example.dtn.model.repository.FriendRepository;
import com.example.dtn.model.repository.MessageRepository;
import com.example.dtn.security.CryptoUtils;
import com.example.dtn.viewmodel.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MainActivity - MVVM Refactored
 *
 * RESPONSIBILITIES (View Layer):
 * - Initialize UI components
 * - Observe ViewModel LiveData
 * - Handle user input events
 * - Delegate business logic to ViewModel
 * - Update UI based on ViewModel state
 */
@SuppressLint({"SetTextI18n", "MissingPermission"})
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ==================== MVVM Components ====================
    private MainViewModel viewModel;

    // ==================== Managers ====================
    private PermissionManager permissionManager;
    private BluetoothManager bluetoothManager;
    private WifiDirectManager wifiDirectManager;
    private ConnectionManager connectionManager;

    // ==================== UI Components ====================
    private TextView statusTextView;
    private TextView protocolTextView;
    private TextView transportTextView;
    private ListView peerListView;
    private ListView chatListView;
    private EditText messageEditText;
    private Button sendButton;
    private Spinner prioritySpinner;
    private Spinner destinationSpinner;
    private Toolbar toolbar;

    // ==================== UI Adapters ====================
    private ArrayAdapter<String> peerAdapter;
    private ArrayAdapter<MainViewModel.ChatMessage> chatAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "           DTN APP STARTING            ");
        Log.d(TAG, "═══════════════════════════════════════");

        // Initialize ViewModel
        initializeViewModel();

        // Initialize UI
        initializeUI();

        // Initialize Managers
        initializeManagers();

        // Setup observers
        setupObservers();

        // Setup listeners
        setupListeners();

        //  Check permissions
        checkPermissions();

        Log.d(TAG, "✅ MainActivity initialization complete");
    }

    // ==================== Initialization Methods ====================

    /**
     * Initialize ViewModel
     */
    private void initializeViewModel() {
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        Log.d(TAG, "✓ ViewModel initialized");
    }

    /**
     * Initialize UI components
     */
    private void initializeUI() {
        // Find views
        statusTextView = findViewById(R.id.statusTextView);
        protocolTextView = findViewById(R.id.protocolTextView);
        transportTextView = findViewById(R.id.transportTextView);
        peerListView = findViewById(R.id.peerListView);
        chatListView = findViewById(R.id.chatListView);
        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);
        prioritySpinner = findViewById(R.id.prioritySpinner);
        destinationSpinner = findViewById(R.id.destinationSpinner);
        toolbar = findViewById(R.id.toolbar);

        // Setup toolbar
        setSupportActionBar(toolbar);

        // Setup adapters
        peerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        peerListView.setAdapter(peerAdapter);

        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        chatListView.setAdapter(chatAdapter);

        // Setup priority spinner
        ArrayAdapter<CharSequence> priorityAdapter = ArrayAdapter.createFromResource(
                this, R.array.priority_options, android.R.layout.simple_spinner_item);
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prioritySpinner.setAdapter(priorityAdapter);
        prioritySpinner.setSelection(0);

        Log.d(TAG, "✓ UI initialized");
    }

    /**
     * Initialize manager classes
     */
    private void initializeManagers() {
        // Permission Manager
        permissionManager = new PermissionManager(this);
        permissionManager.setCallback(new PermissionManager.PermissionCallback() {
            @Override
            public void onPermissionsGranted() {
                Log.d(TAG, "✅ All permissions granted");
                completeInitialization();
            }

            @Override
            public void onPermissionsDenied(List<String> deniedPermissions) {
                Log.w(TAG, "⚠️ Some permissions denied");
                Toast.makeText(MainActivity.this,
                        "Some permissions denied", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPermissionsPermanentlyDenied(List<String> permanentlyDenied) {
                Log.e(TAG, "❌ Permissions permanently denied");
                Toast.makeText(MainActivity.this,
                        "Please enable permissions in Settings", Toast.LENGTH_LONG).show();
            }
        });

        // Bluetooth and WiFi Direct Manager
        bluetoothManager = new BluetoothManager(this, viewModel);
        wifiDirectManager = new WifiDirectManager(this, viewModel);

        // Connection Manager
        connectionManager = new ConnectionManager(this, viewModel,
                bluetoothManager, wifiDirectManager);

        //Inject ConnectionManager
        bluetoothManager.setConnectionManager(connectionManager);
        wifiDirectManager.setConnectionManager(connectionManager);

        Log.d(TAG, "✓ Managers initialized and connected");
    }

    /**
     * Setup LiveData observers
     */
    private void setupObservers() {
        // Observe connection status
        viewModel.getConnectionStatus().observe(this, status -> {
            statusTextView.setText("Status: " + status);
        });

        viewModel.getConnectionColor().observe(this, color -> {
            statusTextView.setTextColor(color);
        });

        // Observe protocol
        viewModel.getProtocolDisplay().observe(this, display -> {
            protocolTextView.setText(display);
        });

        // Observe transport
        viewModel.getTransportDisplay().observe(this, display -> {
            transportTextView.setText(display);
        });

        // Observe chat messages
        viewModel.getChatMessages().observe(this, messages -> {
            chatAdapter.clear();
            chatAdapter.addAll(messages);
            chatAdapter.notifyDataSetChanged();

            if (!messages.isEmpty()) {
                chatListView.smoothScrollToPosition(messages.size() - 1);
            }
        });

        // Observe discovered peers
        viewModel.getDiscoveredPeers().observe(this, peers -> {
            peerAdapter.clear();
            peerAdapter.addAll(peers);
            peerAdapter.notifyDataSetChanged();

            // Update destination spinner
            List<String> destinations = new ArrayList<>();
            destinations.add("broadcast");
            destinations.addAll(peers);
            ArrayAdapter<String> destAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, destinations);
            destAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            destinationSpinner.setAdapter(destAdapter);
        });

        // Observe device ID
        viewModel.getOwnDeviceId().observe(this, deviceId -> {
            Log.d(TAG, "Device ID: " + deviceId);
        });

        Log.d(TAG, "✓ Observers setup");
    }

    /**
     * Setup UI listeners
     */
    private void setupListeners() {
        // Send button
        sendButton.setOnClickListener(v -> sendMessage());

        // Peer list click - connect
        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            connectionManager.connectToPeer(position);
        });

        // Peer list long click - add friend
        peerListView.setOnItemLongClickListener((parent, view, position, id) -> {
            connectionManager.addFriendAtPosition(position);
            return true;
        });

        Log.d(TAG, "✓ Listeners setup");
    }

    // ==================== Permission Handling ====================

    /**
     * Check and request permissions
     */
    private void checkPermissions() {
        if (permissionManager.hasAllRequiredPermissions()) {
            Log.d(TAG, "✅ All permissions already granted");
            completeInitialization();
        } else {
            Log.d(TAG, "Requesting permissions...");
            permissionManager.checkAndRequestPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Complete initialization after permissions granted
     */
    private void completeInitialization() {
        Log.d(TAG, "╔═══════════════════════════════════════╗");
        Log.d(TAG, "  Completing initialization...");
        Log.d(TAG, "╚═══════════════════════════════════════╝");

        // Initialize device name
        String deviceId = bluetoothManager.initializeDeviceName();
        viewModel.setOwnDeviceId(deviceId);

        // Start transport services
        MainViewModel.TransportType activeTransport = viewModel.getActiveTransport().getValue();
        if (activeTransport == MainViewModel.TransportType.BLUETOOTH) {
            bluetoothManager.start();
        } else {
            wifiDirectManager.start();
        }

        // Start connection manager
        connectionManager.start();

        // Update status
        viewModel.setConnectionStatus(true, "Ready");

        Log.d(TAG, "✅ Initialization complete");
    }

    // ==================== Message Sending ====================

    /**
     * Send message
     */
    private void sendMessage() {
        String msgText = messageEditText.getText().toString().trim();

        if (msgText.isEmpty()) {
            Toast.makeText(this, "Message is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create message
        try {
            Message message = new Message();
            message.message_id = UUID.randomUUID().toString();
            message.encrypted_payload = CryptoUtils.encrypt(msgText);
            message.checksum = CryptoUtils.generateChecksum(message.encrypted_payload);
            message.source_id = viewModel.getOwnDeviceId().getValue();

            message.destination_id = getDestinationFromSpinner();

            message.priority = prioritySpinner.getSelectedItem().toString().equals("HIGH") ? 1 : 0;
            message.ttl_timestamp = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2 hours
            message.hop_count = 0;
            message.copy_count = 6;

            // Add to UI immediately with queued status
            boolean connected = Boolean.TRUE.equals(viewModel.getIsConnected().getValue());

            String displayText = "Me: " + msgText + (connected ? "" : " [Queued]");
            viewModel.addChatMessage(displayText, message.message_id, false);

            // Clear input
            messageEditText.setText("");

            // Save to database
            viewModel.insertMessage(message, new MessageRepository.RepositoryCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    Log.d(TAG, "✓ Message saved to database");

                    // Try to send immediately (will queue if not connected)
                    connectionManager.transmitMessage(message);

                    // Show appropriate feedback
                    runOnUiThread(() -> {
                        if (connected) {
                            // Message is being sent
                            Log.d(TAG, "Sending message immediately");
                        } else {
                            // Message queued for later
                            Toast.makeText(MainActivity.this,
                                    "✉️ Message queued - will send when connected",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Error saving message to database", e);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "❌ Error: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();

                        // Remove from UI since save failed
                        viewModel.getChatMessages().getValue().removeIf(
                                msg -> msg.messageId.equals(message.message_id)
                        );
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error creating message", e);
            Toast.makeText(this, "❌ Encryption error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String getDestinationFromSpinner() {
        String selected = destinationSpinner.getSelectedItem().toString();

        // "broadcast" is special case for flooding
        if ("broadcast".equalsIgnoreCase(selected)) {
            return "broadcast";
        }

        // Extract device ID from display string
        String deviceId = selected;

        // Remove connection status if present
        if (deviceId.contains(" ✓ ")) {
            deviceId = deviceId.substring(0, deviceId.indexOf(" ✓ "));
        }

        return deviceId.trim();
    }

    private void flushQueuedMessages() {
        Log.d(TAG, "Flushing queued messages...");
        connectionManager.flushMessageQueue();
    }

    // ==================== Menu ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        // Protocol selection
        if (itemId == R.id.menu_epidemic) {
            viewModel.setProtocol("EPIDEMIC");
            Toast.makeText(this, "✓ Switched to Epidemic Routing", Toast.LENGTH_SHORT).show();
            return true;
        } else if (itemId == R.id.menu_spray_and_wait) {
            viewModel.setProtocol("SPRAY_AND_WAIT");
            Toast.makeText(this, "✓ Switched to Spray and Wait", Toast.LENGTH_SHORT).show();
            return true;
        }

        // Transport selection
        if (itemId == R.id.menu_transport_wifi) {
            switchToWiFiDirect();
            return true;
        } else if (itemId == R.id.menu_transport_bluetooth) {
            switchToBluetooth();
            return true;
        }

        // View friends
        if (itemId == R.id.menu_view_friends) {
            showFriendsDialog();
            Toast.makeText(this, "View Friends - TODO", Toast.LENGTH_SHORT).show();
            return true;
        }

        if (itemId == R.id.menu_clear_messages) {
            showClearMessagesDialog();
            return true;
        }

        if (itemId == R.id.menu_about) {
            showAboutDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Switch to WiFi Direct transport
     */
    private void switchToWiFiDirect() {
        bluetoothManager.stop();
        viewModel.setTransport(MainViewModel.TransportType.WIFI_DIRECT);
        wifiDirectManager.start();
        Toast.makeText(this, "✓ Switched to Wi-Fi Direct", Toast.LENGTH_SHORT).show();
    }

    /**
     * Switch to Bluetooth transport
     */
    private void switchToBluetooth() {
        wifiDirectManager.stop();
        viewModel.setTransport(MainViewModel.TransportType.BLUETOOTH);
        bluetoothManager.start();
        Toast.makeText(this, "✓ Switched to Bluetooth", Toast.LENGTH_SHORT).show();
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        // Reconnect if needed
        if (permissionManager.hasAllRequiredPermissions()) {
            connectionManager.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        connectionManager.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");

        // Cleanup managers
        connectionManager.shutdown();
        bluetoothManager.shutdown();
        wifiDirectManager.shutdown();

        Log.d(TAG, "✓ MainActivity destroyed");
    }

    private void showFriendsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Friends List");

        viewModel.getAllFriends().observe(this, friends -> {
            if (friends == null || friends.isEmpty()) {
                builder.setMessage("No friends added yet.\nLong press on a peer to add as friend.");
            } else {
                String[] friendNames = new String[friends.size()];
                for (int i = 0; i < friends.size(); i++) {
                    Friend f = friends.get(i);
                    friendNames[i] = f.friendlyName + " (" + f.deviceId + ")";
                }
                builder.setItems(friendNames, null);
            }

            builder.setPositiveButton("OK", null);
            builder.show();
        });
    }

    private void showClearMessagesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All Messages")
                .setMessage("This will delete all messages and chat history. Continue?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    viewModel.clearChatMessages();
                    MessageRepository.getInstance(this).deleteAll(new MessageRepository.RepositoryCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            Toast.makeText(MainActivity.this, "Messages cleared", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("About DTN Messenger")
                .setMessage("DTN Messenger v1.0\n\n" +
                        "A Delay-Tolerant Networking application for Android.\n\n" +
                        "Features:\n" +
                        "• Wi-Fi Direct & Bluetooth mesh\n" +
                        "• Epidemic & Spray-and-Wait routing\n" +
                        "• AES-256 encryption\n" +
                        "• Multi-hop message forwarding\n\n" +
                        "Built with Android SDK 34")
                .setPositiveButton("OK", null)
                .show();
    }
}