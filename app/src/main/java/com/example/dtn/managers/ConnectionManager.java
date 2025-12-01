package com.example.dtn.managers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;

import com.example.dtn.model.data.Friend;
import com.example.dtn.model.data.Message;
import com.example.dtn.model.data.MessageDao;
import com.example.dtn.model.repository.FriendRepository;
import com.example.dtn.model.repository.MessageRepository;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.routing.EpidemicRouting;
import com.example.dtn.routing.RoutingProtocol;
import com.example.dtn.routing.SprayAndWaitRouting;
import com.example.dtn.security.CryptoUtils;
import com.example.dtn.viewmodel.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ConnectionManager - Coordinates all connection operations
 * RESPONSIBILITIES:
 * - Coordinate between Bluetooth and WiFi Direct
 * - Handle message sending/receiving
 * - Manage routing protocols
 * - Forward messages
 * - Handle ACKs
 * - Update ViewModel with connection state
 */
public class ConnectionManager {

    private static final String TAG = "ConnectionManager";

    // Message handler codes
    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_CONNECTION_ESTABLISHED = 2;
    public static final int MESSAGE_CONNECTION_LOST = 3;

    private final Context context;
    private final MainViewModel viewModel;
    private final BluetoothManager bluetoothManager;
    private final WifiDirectManager wifiDirectManager;

    private Handler messageHandler;
    private ExecutorService executorService;

    // Routing
    private RoutingProtocol activeRoutingProtocol;
    private EpidemicRouting epidemicRouting;
    private SprayAndWaitRouting sprayAndWaitRouting;

    private final Queue<Message> messageQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_QUEUE_SIZE = 50;
    private Handler forwardingHandler;
    private static final long FORWARDING_INTERVAL = 30000;

    // Dual-stack transport flags
    private boolean bluetoothEnabled = true;
    private boolean wifiDirectEnabled = false;

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public ConnectionManager(Context context, MainViewModel viewModel,
                             BluetoothManager bluetoothManager,
                             WifiDirectManager wifiDirectManager) {
        this.context = context;
        this.viewModel = viewModel;
        this.bluetoothManager = bluetoothManager;
        this.wifiDirectManager = wifiDirectManager;

        this.executorService = Executors.newSingleThreadExecutor();

        initializeHandler();
        initializeRouting();
    }

    /**
     * Start connection manager
     */
    public void start() {
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "  Starting Dual-Stack Transport");
        Log.d(TAG, "═══════════════════════════════════════");

        // ALWAYS start Bluetooth for mesh
        if (bluetoothEnabled && bluetoothManager.isAvailable()) {
            bluetoothManager.start();
            Log.d(TAG, "✓ Bluetooth mesh started");
        }

        // start Wi-Fi Direct for range extension
        if (wifiDirectEnabled && wifiDirectManager.isAvailable()) {
            wifiDirectManager.start();
            Log.d(TAG, "✓ Wi-Fi Direct relay started");
        }

        // Start automatic forwarding
        startAutomaticMeshMode();
        startPeriodicForwarding();

        // Update UI
        updateConnectionStatus();
    }

    /**
     * Update UI to show dual-stack status
     */
    private void updateTransportStatus() {
        int btConnections = 0;
        boolean wifiConnected = false;

        // Count Bluetooth connections
        if (bluetoothManager.getServerThread() != null) {
            btConnections += bluetoothManager.getServerThread().getConnectedClientCount();
        }
        if (bluetoothManager.getClientThreads() != null) {
            btConnections += bluetoothManager.getClientThreads().size();
        }

        // Check Wi-Fi Direct connection
        wifiConnected = wifiDirectManager.isConnected();

        // Build status string
        StringBuilder status = new StringBuilder();

        if (btConnections > 0) {
            status.append("BT Mesh: ").append(btConnections).append(" peer(s)");
        } else {
            status.append("BT Mesh: Searching...");
        }

        if (wifiDirectEnabled) {
            status.append(" | WiFi Relay: ");
            status.append(wifiConnected ? "Connected ✓" : "Standby");
        }

        viewModel.setConnectionStatus(btConnections > 0 || wifiConnected, status.toString());

        Log.d(TAG, "Transport Status: " + status);
    }

    /**
     * Initialize message handler
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void initializeHandler() {
        messageHandler = new Handler(Looper.getMainLooper(), msg -> {

            // Handle messages from WiFi Direct threads
            if (msg.what == ServerThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from WiFi Direct ServerThread");
                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }

            // Handle messages from Bluetooth threads
            if (msg.what == BluetoothClientThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Bluetooth ClientThread");
                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }

            if (msg.what == BluetoothServerThread.MESSAGE_READ) {
                Log.d(TAG, "Handler: Message from Bluetooth ServerThread");
                Message receivedMessage = (Message) msg.obj;
                if (receivedMessage != null) {
                    handleReceivedMessage(receivedMessage);
                }
                return true;
            }

            // Handle connection established
            if (msg.what == MESSAGE_CONNECTION_ESTABLISHED) {
                String deviceName = (String) msg.obj;
                Log.d(TAG, "✓ Connection established with " + deviceName);

                // Update ViewModel
                viewModel.setConnectionStatus(true, "Connected to " + deviceName);

                // Show toast
                Toast.makeText(context, "✓ Connected to " + deviceName,
                        Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    flushMessageQueue();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        triggerForwardingLogic();
                    }, 1000);
                }, 2000);


                return true;
            }

            // Handle connection lost
            if (msg.what == MESSAGE_CONNECTION_LOST) {
                Log.w(TAG, "Connection lost");

                String deviceAddress = null;
                if (msg.obj instanceof String) {
                    deviceAddress = (String) msg.obj;
                }

                // Update ViewModel
                updateConnectionStatus();

                // Show toast
                Toast.makeText(context, "Connection lost", Toast.LENGTH_SHORT).show();

                return true;
            }

            return false;
        });

        Log.d(TAG, "✓ Message handler initialized");
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void triggerForwardingLogic() {
        // Only forward via Bluetooth (mesh capable)
        if (!bluetoothEnabled) {
            Log.d(TAG, "Bluetooth disabled - skipping mesh forwarding");
            return;
        }

        Log.d(TAG, "=== TRIGGERING BLUETOOTH MESH FORWARDING ===");

        viewModel.getMessagesToForward(new MessageRepository.RepositoryCallback<List<Message>>() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Override
            public void onSuccess(List<Message> messages) {
                if (messages == null || messages.isEmpty()) {
                    Log.d(TAG, "No messages to forward");
                    return;
                }

                Log.d(TAG, "Found " + messages.size() + " messages to forward");

                // Get connected Bluetooth devices
                List<BluetoothDevice> connectedDevices = new ArrayList<>();

                List<BluetoothClientThread> clientThreads = bluetoothManager.getClientThreads();
                if (clientThreads != null) {
                    for (BluetoothClientThread client : clientThreads) {
                        if (client != null && client.isConnected()) {
                            BluetoothDevice device = client.getRemoteDevice();
                            if (device != null) {
                                connectedDevices.add(device);
                            }
                        }
                    }
                }

                Log.d(TAG, "Forwarding to " + connectedDevices.size() + " Bluetooth device(s)");

                // Use routing protocol
                if ("SPRAY_AND_WAIT".equals(viewModel.getCurrentProtocol().getValue())) {
                    sprayAndWaitRouting.forwardMessagesToMultipleDevicesBluetooth(
                            messages, connectedDevices,
                            bluetoothManager.getServerThread(),
                            bluetoothManager.getClientThreads()
                    );
                } else {
                    epidemicRouting.forwardMessagesToMultipleDevicesBluetooth(
                            messages, connectedDevices,
                            bluetoothManager.getServerThread(),
                            bluetoothManager.getClientThreads()
                    );
                }

                Log.d(TAG, "=== BLUETOOTH MESH FORWARDING COMPLETE ===");
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Error getting messages to forward", e);
            }
        });
    }

    /**
     * Initialize routing protocols
     */
    private void initializeRouting() {
        String ownDeviceId = viewModel.getOwnDeviceId().getValue();

        if (ownDeviceId == null || ownDeviceId.isEmpty()) {
            ownDeviceId = "unknown";
        }

        // Get MessageDao from Repository
        MessageDao messageDao = MessageRepository.getInstance(context).getMessageDao();

        // Initialize routing protocols with MessageDao
        epidemicRouting = new EpidemicRouting(context, ownDeviceId, messageDao);
        sprayAndWaitRouting = new SprayAndWaitRouting(context, ownDeviceId, messageDao);

        // Set active protocol based on ViewModel
        String currentProtocol = viewModel.getCurrentProtocol().getValue();
        if ("SPRAY_AND_WAIT".equals(currentProtocol)) {
            activeRoutingProtocol = sprayAndWaitRouting;
        } else {
            activeRoutingProtocol = epidemicRouting;
        }

        Log.d(TAG, "✓ Routing protocols initialized");
    }

    /**
     * Resume connections
     */
    public void resume() {
        Log.d(TAG, "ConnectionManager resumed");
        updateConnectionStatus();
    }

    /**
     * Pause connections
     */
    public void pause() {
        Log.d(TAG, "ConnectionManager paused");

    }

    /**
     * Connect to peer at position
     */
    public void connectToPeer(int position) {
        var device = bluetoothManager.getDeviceAtPosition(position);
        if (device != null) {
            bluetoothManager.connectToDevice(device);
        }else {
            Log.w(TAG, "Device at position " + position + " not found");
        }
    }

    /**
     * Add friend at position
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void addFriendAtPosition(int position) {
        var device = bluetoothManager.getDeviceAtPosition(position);
        if (device != null) {
            String deviceId = device.getAddress();
            String deviceName = getDeviceName(device);
            addFriend(deviceId, deviceName);
        } else {
            Log.w(TAG, "Device at position " + position + " not found");
        }
    }
    @SuppressLint("MissingPermission")
    private String getDeviceName(android.bluetooth.BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    /**
     * Add friend
     */
    private void addFriend(String deviceId, String deviceName) {
        Friend friend = new Friend();
        friend.deviceId = deviceId;
        friend.friendlyName = deviceName;
        friend.lastEncounteredTimestamp = System.currentTimeMillis();

        viewModel.insertOrUpdateFriend(friend, new FriendRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Log.d(TAG, "✓ Friend added: " + deviceName);
                Toast.makeText(context, deviceName + " added as friend",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Error adding friend", e);
                Toast.makeText(context, "Error adding friend: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Send message via active transport
     */
    public void transmitMessage(Message message) {
        executorService.execute(() -> {
            try {
                boolean sent = false;
                int totalDevicesSentTo = 0;

                // ═══ BLUETOOTH MESH (Primary) ═══
                if (bluetoothEnabled) {
                    int btDevices = 0;

                    BluetoothServerThread btServerThread = bluetoothManager.getServerThread();
                    List<BluetoothClientThread> btClientThreads = bluetoothManager.getClientThreads();

                    // Send via all Bluetooth client connections
                    if (btClientThreads != null) {
                        for (BluetoothClientThread client : btClientThreads) {
                            if (client != null && client.isConnected()) {
                                client.write(message);
                                sent = true;
                                btDevices++;
                            }
                        }
                    }

                    // Broadcast via Bluetooth server
                    if (btServerThread != null && btServerThread.isAlive()) {
                        int serverClients = btServerThread.getConnectedClientCount();
                        if (serverClients > 0) {
                            btServerThread.broadcastToAll(message);
                            sent = true;
                            btDevices += serverClients;
                        }
                    }

                    totalDevicesSentTo += btDevices;

                    if (btDevices > 0) {
                        Log.d(TAG, "✓ Bluetooth: Sent to " + btDevices + " device(s)");
                    }
                }

                // ═══ WI-FI DIRECT RELAY (Range Extension) ═══
                if (wifiDirectEnabled && wifiDirectManager.isConnected()) {
                    ServerThread wifiServer = wifiDirectManager.getServerThread();
                    ClientThread wifiClient = wifiDirectManager.getClientThread();

                    if (wifiServer != null && wifiServer.isConnected()) {
                        wifiServer.write(message);
                        sent = true;
                        totalDevicesSentTo++;
                        Log.d(TAG, "✓ Wi-Fi Direct: Relayed via server");
                    } else if (wifiClient != null && wifiClient.isConnected()) {
                        wifiClient.write(message);
                        sent = true;
                        totalDevicesSentTo++;
                        Log.d(TAG, "✓ Wi-Fi Direct: Relayed via client");
                    }
                }

                // ═══ QUEUE IF NOT SENT ═══
                if (!sent) {
                    queueMessage(message);
                    Log.d(TAG, "📥 Message queued: " + message.message_id);
                }

                final boolean finalSent = sent;
                final int finalDevicesSentTo = totalDevicesSentTo;

                // Update UI
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (finalSent) {
                        String msg = "✓ Sent to " + finalDevicesSentTo + " device(s)";
                        if (bluetoothEnabled && wifiDirectEnabled) {
                            msg += " (BT+WiFi)";
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "❌ Send error: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /**
     * Add message to queue (with size limit)
     */
    private void queueMessage(Message message) {
        synchronized (messageQueue) {
            if (messageQueue.size() >= MAX_QUEUE_SIZE) {
                Message removed = messageQueue.poll();
                Log.w(TAG, "⚠️ Queue full - removed oldest message: " +
                        (removed != null ? removed.message_id : "unknown"));
            }

            messageQueue.offer(message);
            Log.d(TAG, "📥 Queued message: " + message.message_id +
                    " (Queue size: " + messageQueue.size() + ")");
        }
    }

    /**
     * Flush all queued messages when connection is established
     */
    public void flushMessageQueue() {
        int queueSize = messageQueue.size();

        if (queueSize == 0) {
            Log.d(TAG, "No queued messages to flush");
            return;
        }

        Log.d(TAG, "📤 Flushing " + queueSize + " queued messages...");

        executorService.execute(() -> {
            int successCount = 0;
            int failCount = 0;

            while (!messageQueue.isEmpty()) {
                Message msg = messageQueue.poll();
                if (msg != null) {
                    try {
                        // Check if message expired
                        if (msg.ttl_timestamp < System.currentTimeMillis()) {
                            Log.w(TAG, "⏰ Message expired: " + msg.message_id);
                            failCount++;
                            continue;
                        }

                        // Send message (without re-queueing)
                        sendMessageDirectly(msg);
                        successCount++;

                        // Small delay between messages
                        Thread.sleep(100);

                    } catch (Exception e) {
                        Log.e(TAG, "Error flushing message: " + msg.message_id, e);
                        failCount++;
                    }
                }
            }

            final int finalSuccess = successCount;
            final int finalFail = failCount;

            new Handler(Looper.getMainLooper()).post(() -> {
                String msg = "✓ Sent " + finalSuccess + " queued message(s)";
                if (finalFail > 0) {
                    msg += " (" + finalFail + " failed/expired)";
                }

                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                Log.d(TAG, "📤 Queue flush complete: " + finalSuccess + " sent, " +
                        finalFail + " failed");
            });
        });
    }

    /**
     * Send message directly without queueing
     */
    private void sendMessageDirectly(Message message) throws Exception {
        // Send via Bluetooth
        if (bluetoothEnabled) {
            BluetoothServerThread btServerThread = bluetoothManager.getServerThread();
            List<BluetoothClientThread> btClientThreads = bluetoothManager.getClientThreads();

            if (btClientThreads != null) {
                for (BluetoothClientThread client : btClientThreads) {
                    if (client != null && client.isConnected()) {
                        client.write(message);
                    }
                }
            }

            if (btServerThread != null && btServerThread.isAlive()) {
                btServerThread.broadcastToAll(message);
            }
        }

        // Send via Wi-Fi Direct (if enabled)
        if (wifiDirectEnabled && wifiDirectManager.isConnected()) {
            ServerThread serverThread = wifiDirectManager.getServerThread();
            ClientThread clientThread = wifiDirectManager.getClientThread();

            if (serverThread != null && serverThread.isConnected()) {
                serverThread.write(message);
            } else if (clientThread != null && clientThread.isConnected()) {
                clientThread.write(message);
            }
        }
    }

    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return messageQueue.size();
    }

    /**
     * Clear message queue
     */
    public void clearMessageQueue() {
        int cleared = messageQueue.size();
        messageQueue.clear();
        Log.d(TAG, "🗑️ Cleared " + cleared + " queued messages");
    }

    /**
     * Handle received message
     */
    private void handleReceivedMessage(Message message) {
        executorService.execute(() -> {
            try {
                Log.d(TAG, "=== Processing Received Message ===");
                Log.d(TAG, "Message ID: " + message.message_id);
                Log.d(TAG, "From: " + message.source_id);
                Log.d(TAG, "To: " + message.destination_id);

                // Validate checksum
                boolean checksumValid = CryptoUtils.validateChecksum(
                        message.encrypted_payload, message.checksum);

                if (!checksumValid) {
                    Log.e(TAG, "Checksum failed - message tampered!");
                    return;
                }

                // Process based on message type
                if (message.message_type == Message.TYPE_ACK) {
                    processAck(message);
                } else if (message.message_type == Message.TYPE_DATA) {
                    processDataMessage(message);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling message", e);
            }
        });
    }

    /**
     * Process data message
     */
    private void processDataMessage(Message message) throws Exception {
        String myDeviceId = viewModel.getOwnDeviceId().getValue();

        if (myDeviceId == null || myDeviceId.isEmpty()) {
            Log.e(TAG, "Cannot process message - device ID not initialized");
            return;
        }

        boolean isForMe = myDeviceId.equals(message.destination_id);

        if (isForMe) {
            // Message is for me - decrypt and display
            String decryptedText = CryptoUtils.decrypt(message.encrypted_payload);
            Log.d(TAG, "✓ Decrypted: " + decryptedText);

            // Add to UI
            viewModel.addChatMessage("Peer: " + decryptedText, message.message_id, true);

            // Mark as delivered
            message.is_delivered = true;
            viewModel.updateMessage(message, null);

            // Send ACK
            try {
                Message ackMessage = new Message();
                ackMessage.message_id = UUID.randomUUID().toString();
                ackMessage.message_type = Message.TYPE_ACK;
                ackMessage.source_id = myDeviceId;
                ackMessage.destination_id = message.source_id;
                ackMessage.encrypted_payload = CryptoUtils.encrypt(message.message_id);
                ackMessage.checksum = CryptoUtils.generateChecksum(ackMessage.encrypted_payload);
                ackMessage.ttl_timestamp = System.currentTimeMillis() + (30 * 60 * 1000); // 30 min
                ackMessage.hop_count = 0;
                ackMessage.copy_count = 1;

                transmitMessage(ackMessage);
                Log.d(TAG, "✓ ACK sent for message: " + message.message_id);
            } catch (Exception e) {
                Log.e(TAG, "Error sending ACK", e);
            }

        } else {
            // Message not for me - forward
            Log.d(TAG, "✗ Message NOT for me, forwarding...");

            message.hop_count++;

            if (message.hop_count >= 15) {
                Log.w(TAG, "Message exceeded hop limit");
                return;
            }

            // Update in database
            viewModel.updateMessage(message, new MessageRepository.RepositoryCallback<Void>() {
                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                @Override
                public void onSuccess(Void result) {
                    Log.d(TAG, "✓ Message stored, triggering mesh forwarding");

                    // Forward via all available transport
                    transmitMessage(message);

                    // Trigger mesh forwarding
                    triggerForwardingLogic();
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Error storing message", e);
                }
            });
        }
    }

    /**
     * Process ACK message
     */
    private void processAck(Message ackMessage) throws Exception {
        String myDeviceId = viewModel.getOwnDeviceId().getValue();

        if (!myDeviceId.equals(ackMessage.destination_id)) {
            // ACK not for me, forward it
            transmitMessage(ackMessage);
            return;
        }

        // ACK is for me
        String originalMessageId = CryptoUtils.decrypt(ackMessage.encrypted_payload);

        // Update original message delivery status
        viewModel.updateChatMessageDelivery(originalMessageId, true);

        Log.d(TAG, "✓ Delivery confirmed for: " + originalMessageId);

        Toast.makeText(context, "✓ Delivery Confirmed!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Start automatic mesh mode (for Bluetooth)
     */
    private void startAutomaticMeshMode() {
        Log.d(TAG, "Starting automatic mesh mode...");

        Handler discoveryHandler = new Handler(Looper.getMainLooper());

        Runnable discoveryRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Auto-discovery scan...");
                if (bluetoothEnabled && bluetoothManager.isAvailable()) {
                    bluetoothManager.startDiscovery();
                }
                discoveryHandler.postDelayed(this, 60000); // Every 60s
            }
        };

        discoveryHandler.post(discoveryRunnable);
    }

    /**
     * Update connection status
     */
    private void updateConnectionStatus() {
        int btConnections = 0;
        boolean wifiConnected = false;

        // Count Bluetooth connections
        if (bluetoothManager.getServerThread() != null) {
            btConnections += bluetoothManager.getServerThread().getConnectedClientCount();
        }
        if (bluetoothManager.getClientThreads() != null) {
            for (BluetoothClientThread client : bluetoothManager.getClientThreads()) {
                if (client != null && client.isConnected()) {
                    btConnections++;
                }
            }
        }

        // Check Wi-Fi Direct connection
        wifiConnected = wifiDirectEnabled && wifiDirectManager.isConnected();

        // Build status string
        StringBuilder status = new StringBuilder();

        if (btConnections > 0) {
            status.append("BT Mesh: ").append(btConnections).append(" peer(s)");
        } else {
            status.append("BT Mesh: Searching...");
        }

        if (wifiDirectEnabled) {
            status.append(" | WiFi Relay: ");
            status.append(wifiConnected ? "Connected ✓" : "Standby");
        }

        viewModel.setConnectionStatus(btConnections > 0 || wifiConnected, status.toString());

        Log.d(TAG, "Connection Status: " + status);
    }

    /**
     * Enable/disable Wi-Fi Direct relay
     */
    public void enableWifiDirectRelay(boolean enable) {
        wifiDirectEnabled = enable;

        if (enable) {
            if (wifiDirectManager.isAvailable()) {
                wifiDirectManager.start();
                Toast.makeText(context, "✓ Wi-Fi Direct relay enabled (range boost)",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "❌ Wi-Fi Direct not available",
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            wifiDirectManager.stop();
            Toast.makeText(context, "Wi-Fi Direct relay disabled",
                    Toast.LENGTH_SHORT).show();
        }

        updateTransportStatus();
    }

    /**
     * FIXED: Start periodic forwarding of stored messages
     */
    private void startPeriodicForwarding() {
        if (forwardingHandler != null) {
            forwardingHandler.removeCallbacksAndMessages(null);
        }

        forwardingHandler = new Handler(Looper.getMainLooper());

        Runnable forwardingRunnable = new Runnable() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Override
            public void run() {
                // Check if we have active connections
                boolean hasBluetoothConnections = false;

                BluetoothServerThread server = bluetoothManager.getServerThread();
                List<BluetoothClientThread> clients = bluetoothManager.getClientThreads();

                hasBluetoothConnections = (server != null && server.getConnectedClientCount() > 0) ||
                        (clients != null && !clients.isEmpty());

                if (hasBluetoothConnections) {
                    Log.d(TAG, "⏰ Periodic forwarding - triggering");
                    triggerForwardingLogic();
                }

                updateConnectionStatus();

                // Schedule next check
                if (forwardingHandler != null) {
                    forwardingHandler.postDelayed(this, FORWARDING_INTERVAL);
                }
            }
        };

        forwardingHandler.postDelayed(forwardingRunnable, FORWARDING_INTERVAL);
        Log.d(TAG, "✓ Periodic forwarding started (every 30s)");
    }

    /**
     * Stop periodic forwarding
     */
    private void stopPeriodicForwarding() {
        if (forwardingHandler != null) {
            forwardingHandler.removeCallbacksAndMessages(null);
            forwardingHandler = null;
            Log.d(TAG, "✓ Periodic forwarding stopped");
        }
    }

    /**
     * Get message handler
     */
    public Handler getMessageHandler() {
        return messageHandler;
    }

    /**
     * Shutdown
     */
    public void shutdown() {
        stopPeriodicForwarding();

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        if (epidemicRouting != null) {
            epidemicRouting.shutdown();
        }

        if (sprayAndWaitRouting != null) {
            sprayAndWaitRouting.shutdown();
        }

        Log.d(TAG, "✓ ConnectionManager shutdown");
    }
}