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

import com.example.dtn.data.Friend;
import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ConnectionManager - Coordinates all connection operations
 *
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
     * Initialize message handler
     */
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
                    triggerForwardingLogic();
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

    private void triggerForwardingLogic() {
        viewModel.getMessagesToForward(new MessageRepository.RepositoryCallback<List<Message>>() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Override
            public void onSuccess(List<Message> messages) {
                if (messages == null || messages.isEmpty()) {
                    Log.d(TAG, "No messages to forward");
                    return;
                }

                // Forward via active transport
                MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();

                if (transport == MainViewModel.TransportType.BLUETOOTH) {
                    // Get connected Bluetooth devices
                    List<BluetoothDevice> connectedDevices = new ArrayList<>();
                    // Add logic to get connected devices

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
                }
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
     * Start connection manager
     */
    public void start() {
        Log.d(TAG, "ConnectionManager started");

        // Start automatic mesh mode if Bluetooth
        MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();
        if (transport == MainViewModel.TransportType.BLUETOOTH) {
            startAutomaticMeshMode();
        }
    }

    /**
     * Resume connections
     */
    public void resume() {
        Log.d(TAG, "ConnectionManager resumed");
        // TODO: Re-register receivers
    }

    /**
     * Pause connections
     */
    public void pause() {
        Log.d(TAG, "ConnectionManager paused");
        // TODO: Unregister receivers
    }

    /**
     * Connect to peer at position
     */
    public void connectToPeer(int position) {
        MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();

        if (transport == MainViewModel.TransportType.WIFI_DIRECT) {
            // WiFi Direct connection
            var device = wifiDirectManager.getDeviceAtPosition(position);
            if (device != null) {
                wifiDirectManager.connectToPeer(device);
            }

        } else if (transport == MainViewModel.TransportType.BLUETOOTH) {
            var device = bluetoothManager.getDeviceAtPosition(position);
            if (device != null) {
                bluetoothManager.connectToDevice(device);
            }
        }
    }

    /**
     * Add friend at position
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void addFriendAtPosition(int position) {
        MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();

        if (transport == MainViewModel.TransportType.WIFI_DIRECT) {
            var device = wifiDirectManager.getDeviceAtPosition(position);
            if (device != null) {
                addFriend(device.deviceAddress, device.deviceName);
            }

        } else if (transport == MainViewModel.TransportType.BLUETOOTH) {
            var device = bluetoothManager.getDeviceAtPosition(position);
            if(device != null){
                String deviceId = device.getAddress();
                String deviceName = getDeviceName(device);
                addFriend(deviceId,deviceName);
            }
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
    public void sendMessage(Message message) {
        executorService.execute(() -> {
            try {
                boolean sent = false;
                int devicesSentTo = 0;

                MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();

                if (transport == MainViewModel.TransportType.BLUETOOTH) {
                    BluetoothServerThread btServerThread = bluetoothManager.getServerThread();
                    List<BluetoothClientThread> btClientThreads = bluetoothManager.getClientThreads();

                    // Send via all connected Bluetooth clients
                    if (btClientThreads != null) {
                        for (BluetoothClientThread client : btClientThreads) {
                            if (client != null && client.isConnected()) {
                                client.write(message);
                                sent = true;
                                devicesSentTo++;
                            }
                        }
                    }

                    // Broadcast via server thread
                    if (btServerThread != null && btServerThread.isAlive()) {
                        btServerThread.broadcastToAll(message);
                        sent = true;
                        devicesSentTo++;
                    }

                    Log.d(TAG, "✓ Sent via Bluetooth to " + devicesSentTo + " device(s)");

                } else if (transport == MainViewModel.TransportType.WIFI_DIRECT) {
                    // Send via WiFi Direct
                    ServerThread serverThread = wifiDirectManager.getServerThread();
                    ClientThread clientThread = wifiDirectManager.getClientThread();

                    if (serverThread != null && serverThread.isConnected()) {
                        serverThread.write(message);
                        sent = true;
                        devicesSentTo++;
                        Log.d(TAG, "✓ Sent via WiFi Direct ServerThread");
                    } else if (clientThread != null && clientThread.isConnected()) {
                        clientThread.write(message);
                        sent = true;
                        devicesSentTo++;
                        Log.d(TAG, "✓ Sent via WiFi Direct ClientThread");
                    }
                }

                final boolean finalSent = sent;
                final int finalDevicesSentTo = devicesSentTo;

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (finalSent) {
                        String successMsg = finalDevicesSentTo > 1
                                ? "✓ Broadcasted to " + finalDevicesSentTo + " devices"
                                : "✓ Message sent";
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "No active connection - message queued",
                                Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);

                // Show error toast
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "Error sending message: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show()
                );
            }
        });
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

                sendMessage(ackMessage);
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

            viewModel.updateMessage(message, null);

            // Forward via active transport
            sendMessage(message);
        }
    }

    /**
     * Process ACK message
     */
    private void processAck(Message ackMessage) throws Exception {
        String myDeviceId = viewModel.getOwnDeviceId().getValue();

        if (!myDeviceId.equals(ackMessage.destination_id)) {
            // ACK not for me, forward it
            sendMessage(ackMessage);
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

                if (bluetoothManager.isAvailable()) {
                    bluetoothManager.startDiscovery();
                }

                // Repeat every 60 seconds
                discoveryHandler.postDelayed(this, 60000);
            }
        };

        // Start first scan immediately
        discoveryHandler.post(discoveryRunnable);
    }

    /**
     * Update connection status
     */
    private void updateConnectionStatus() {
        MainViewModel.TransportType transport = viewModel.getActiveTransport().getValue();

        if (transport == MainViewModel.TransportType.BLUETOOTH) {
            bluetoothManager.updateConnectionStatus();

        } else if (transport == MainViewModel.TransportType.WIFI_DIRECT) {
            if (wifiDirectManager.isConnected()) {
                viewModel.setConnectionStatus(true, "Connected");
            } else {
                viewModel.setConnectionStatus(false, "Disconnected");
            }
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