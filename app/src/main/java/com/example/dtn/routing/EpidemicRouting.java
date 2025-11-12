package com.example.dtn.routing;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EpidemicRouting implements RoutingProtocol {

    private static final String TAG = "EpidemicRouting";
    private static final int MAX_HOPS = 15; // FIXED: Prevent infinite loops

    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    private final ExecutorService dbExecutor;

    // FIXED: Track which messages each peer has seen
    private final Map<String, Set<String>> peerMessageHistory;
    private final Object historyLock = new Object();

    public EpidemicRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.dbExecutor = Executors.newSingleThreadExecutor();
        this.peerMessageHistory = new HashMap<>();
    }

    @Override
    public void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer,
                                ServerThread serverThread, ClientThread clientThread) {

        if (peer == null || (peer.deviceName == null && peer.deviceAddress == null)) {
            Log.e(TAG, "Invalid peer device");
            return;
        }

        if (messagesToForward == null || messagesToForward.isEmpty()) {
            Log.d(TAG, "No messages to forward");
            return;
        }

        String peerId = peer.deviceAddress != null ? peer.deviceAddress : peer.deviceName;

        for (Message message : messagesToForward) {
            // Use consistent device identifier
            if (message.source_id != null) {
                String sourceId = message.source_id;
                if (sourceId.equals(peerId)) {
                    Log.d(TAG, "Skipping message from sender: " + message.message_id);
                    continue;
                }
            }

            // Check hop count limit
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message reached max hops: " + message.message_id);
                continue;
            }

            // Check if peer already has this message
            if (hasPeerSeenMessage(peerId, message.message_id)) {
                Log.d(TAG, "Peer already has message: " + message.message_id);
                continue;
            }

            forwardMessage(message, peer, peerId, serverThread, clientThread);
            recordMessageForPeer(peerId, message.message_id);
        }

        int forwardedCount = messagesToForward.size();
        Log.d(TAG, String.format(Locale.US, "Epidemic: Forwarded %d messages to %s",
                forwardedCount, peerId));
    }

    /**
     *  Forward a single message
     */
    private void forwardMessage(Message message, WifiP2pDevice peer, String peerId,
                                ServerThread serverThread, ClientThread clientThread) {
        message.hop_count++;

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=EPIDEMIC | MSG_ID=%s | FROM=%s | TO=%s | HOPS=%d | DEST=%s",
                message.message_id, ownDeviceId, peerId, message.hop_count, message.destination_id));

        boolean sent = false;

        if (serverThread != null && serverThread.isConnected()) {
            serverThread.write(message);
            sent = true;
            Log.d(TAG, String.format("Sent message %s via ServerThread", message.message_id));
        } else if (clientThread != null && clientThread.isConnected()) {
            clientThread.write(message);
            sent = true;
            Log.d(TAG, String.format("Sent message %s via ClientThread", message.message_id));
        }

        if (!sent) {
            Log.e(TAG, "Failed to send message: " + message.message_id + " - no active thread");
            logger.logEvent(String.format(Locale.US,
                    "EVENT=SEND_FAILED | MSG_ID=%s | REASON=NO_ACTIVE_THREAD", message.message_id));
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void forwardMessagesBluetooth(List<Message> messagesToForward, BluetoothDevice peer,
                                         BluetoothServerThread serverThread,
                                         BluetoothClientThread clientThread) {

        if (peer == null || peer.getName() == null) {
            Log.e(TAG, "Invalid Bluetooth peer");
            return;
        }

        if (messagesToForward == null || messagesToForward.isEmpty()) {
            Log.d(TAG, "No messages to forward");
            return;
        }

        String peerId = peer.getName();  // Use Bluetooth device name
        String peerAddress = peer.getAddress();

        for (Message message : messagesToForward) {
            // Skip if from this peer
            if (message.source_id != null &&
                    (message.source_id.equals(peerId) || message.source_id.equals(peerAddress))) {
                Log.d(TAG, "Skipping message from sender: " + message.message_id);
                continue;
            }

            // Check hop count
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message reached max hops: " + message.message_id);
                continue;
            }

            // Check if peer already has message
            if (hasPeerSeenMessage(peerId, message.message_id)) {
                Log.d(TAG, "Peer already has message: " + message.message_id);
                continue;
            }

            // Forward via Bluetooth
            forwardMessageBluetooth(message, peer, peerId, serverThread, clientThread);
            recordMessageForPeer(peerId, message.message_id);
        }

        Log.d(TAG, String.format(Locale.US, "Epidemic: Forwarded %d messages to %s (Bluetooth)",
                messagesToForward.size(), peerId));
    }

    private void forwardMessageBluetooth(Message message, BluetoothDevice peer, String peerId,
                                         BluetoothServerThread serverThread,
                                         BluetoothClientThread clientThread) {
        message.hop_count++;

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=EPIDEMIC | TRANSPORT=BLUETOOTH | MSG_ID=%s | FROM=%s | TO=%s | HOPS=%d",
                message.message_id, ownDeviceId, peerId, message.hop_count));

        boolean sent = false;

        if (clientThread != null && clientThread.isAlive()) {
            clientThread.write(message);
            sent = true;
            Log.d(TAG, String.format("Sent message %s via Bluetooth ClientThread", message.message_id));
        } else if (serverThread != null && serverThread.isAlive()) {
            serverThread.broadcastToAll(message);
            sent = true;
            Log.d(TAG, String.format("Sent message %s via Bluetooth ServerThread", message.message_id));
        }

        if (!sent) {
            Log.e(TAG, "Failed to send message via Bluetooth: " + message.message_id);
            logger.logEvent(String.format(Locale.US,
                    "EVENT=SEND_FAILED | TRANSPORT=BLUETOOTH | MSG_ID=%s | REASON=NO_ACTIVE_THREAD",
                    message.message_id));
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void forwardMessagesToMultipleDevicesBluetooth(
            List<Message> messagesToForward,
            List<BluetoothDevice> connectedDevices,
            BluetoothServerThread serverThread,
            List<BluetoothClientThread> clientThreads) {

        if (messagesToForward == null || messagesToForward.isEmpty()) {
            Log.d(TAG, "No messages to forward");
            return;
        }

        if (connectedDevices == null || connectedDevices.isEmpty()) {
            Log.d(TAG, "No connected devices");
            return;
        }

        Log.d(TAG, "=== MESH FORWARDING START ===");
        Log.d(TAG, "Messages to forward: " + messagesToForward.size());
        Log.d(TAG, "Connected devices: " + connectedDevices.size());

        int totalForwarded = 0;

        // Process each message
        for (Message message : messagesToForward) {

            // Check hop count limit
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message " + message.message_id + " reached max hops");
                continue;
            }

            Set<String> forwardedTo = new HashSet<>();

            // Forward to each connected peer
            for (BluetoothDevice peer : connectedDevices) {
                try {
                    String peerId = peer.getName();
                    String peerAddress = peer.getAddress();

                    // Skip if message is from this peer
                    if (message.source_id != null &&
                            (message.source_id.equals(peerId) || message.source_id.equals(peerAddress))) {
                        Log.d(TAG, "Skipping message from sender: " + peerId);
                        continue;
                    }

                    // Skip if peer already has this message
                    if (hasPeerSeenMessage(peerId, message.message_id)) {
                        Log.d(TAG, "Peer " + peerId + " already has message");
                        continue;
                    }

                    // Skip if already forwarded to this peer in this batch
                    if (forwardedTo.contains(peerAddress)) {
                        continue;
                    }

                    // Increment hop count for this transmission
                    Message messageCopy = copyMessage(message);
                    messageCopy.hop_count++;

                    // Try to send via client threads first
                    boolean sent = false;
                    if (clientThreads != null) {
                        for (BluetoothClientThread client : clientThreads) {
                            if (client.getRemoteDeviceAddress().equals(peerAddress) &&
                                    client.isConnected()) {
                                client.write(messageCopy);
                                sent = true;
                                forwardedTo.add(peerAddress);
                                totalForwarded++;

                                Log.d(TAG, "✓ Forwarded " + message.message_id +
                                        " to " + peerId + " via client (hop " + messageCopy.hop_count + ")");
                                break;
                            }
                        }
                    }

                    // If not sent via client, try server
                    if (!sent && serverThread != null && serverThread.isAlive()) {
                        serverThread.sendToClient(peerAddress, messageCopy);
                        forwardedTo.add(peerAddress);
                        totalForwarded++;

                        Log.d(TAG, "✓ Forwarded " + message.message_id +
                                " to " + peerId + " via server (hop " + messageCopy.hop_count + ")");
                    }

                    // Record that peer has seen this message
                    if (forwardedTo.contains(peerAddress)) {
                        recordMessageForPeer(peerId, message.message_id);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error forwarding to peer", e);
                }
            }

            // Update database with new hop count
            if (!forwardedTo.isEmpty()) {
                message.hop_count++;
                try {
                    messageDao.update(message);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating message hop count", e);
                }
            }
        }

        Log.d(TAG, "=== MESH FORWARDING COMPLETE ===");
        Log.d(TAG, "Total forwards: " + totalForwarded);

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESH_FORWARDING | PROTOCOL=EPIDEMIC | MESSAGES=%d | FORWARDS=%d | PEERS=%d",
                messagesToForward.size(), totalForwarded, connectedDevices.size()));
    }

    /**
     * Helper method to create a copy of a message
     */
    private Message copyMessage(Message original) {
        Message copy = new Message();
        copy.message_id = original.message_id;
        copy.message_type = original.message_type;
        copy.source_id = original.source_id;
        copy.destination_id = original.destination_id;
        copy.encrypted_payload = original.encrypted_payload;
        copy.checksum = original.checksum;
        copy.priority = original.priority;
        copy.ttl_timestamp = original.ttl_timestamp;
        copy.hop_count = original.hop_count;
        copy.copy_count = original.copy_count;
        copy.is_delivered = original.is_delivered;
        return copy;
    }

    /**
     * Check if peer has seen this message
     */
    private boolean hasPeerSeenMessage(String peerId, String messageId) {
        synchronized (historyLock) {
            Set<String> messages = peerMessageHistory.getOrDefault(peerId, new HashSet<>());
            return messages.contains(messageId);
        }
    }

    /**
     * Record message as forwarded to peer
     */
    private void recordMessageForPeer(String peerId, String messageId) {
        synchronized (historyLock) {
            peerMessageHistory.computeIfAbsent(peerId, k -> new HashSet<>()).add(messageId);
        }
    }

    /**
     * Clear history for a disconnected peer to free memory
     */
    public void clearPeerHistory(String peerId) {
        synchronized (historyLock) {
            peerMessageHistory.remove(peerId);
            Log.d(TAG, "Cleared history for peer: " + peerId);
        }
    }

    public List<Message> getMessagesToForward() {
        return messageDao.getNonExpiredMessages(System.currentTimeMillis());
    }

    /**
     * Shutdown executor
     */
    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            Log.d(TAG, "Executor shutdown");
        }
    }
}
