package com.example.dtn.routing;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.model.data.Message;
import com.example.dtn.model.data.MessageDao;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.utils.DeviceIdentifier;
import com.example.dtn.utils.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EpidemicRouting - Flooding-based DTN routing protocol
 *
 * ALGORITHM:
 * - Forward ALL messages to ALL encountered peers
 * - Maximizes delivery probability
 * - High network overhead (many duplicates)
 * - Best for: Dense networks, critical messages
 *
 * IMPLEMENTATION:
 * - Tracks which messages each peer has seen (prevents redundant sends)
 * - Updates hop count for each transmission
 * - Respects hop limit (max 15 hops)
 * - Uses Bluetooth mesh topology
 */
public class EpidemicRouting implements RoutingProtocol {

    private static final String TAG = "EpidemicRouting";

    // Protocol parameters
    private static final int MAX_HOPS = 15;
    private static final int MAX_PEER_HISTORY = 100;
    private static final int MAX_MESSAGES_PER_PEER = 1000;

    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    private final ExecutorService dbExecutor;

    // Track which messages each peer has seen (duplicate prevention)
    private final Map<String, Set<String>> peerMessageHistory;
    private final Object historyLock = new Object();

    public EpidemicRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.dbExecutor = Executors.newSingleThreadExecutor();
        this.peerMessageHistory = new HashMap<>();

        Log.d(TAG, "✓ EpidemicRouting initialized for device: " + ownDeviceId);
    }

    /**
     * Forward messages to multiple Bluetooth devices (mesh routing)
     *
     * EPIDEMIC ALGORITHM:
     * 1. For each message in the queue:
     *    a. Check if message expired or exceeded hop limit
     *    b. For each connected peer:
     *       - Check if peer already has this message
     *       - If not, forward message to peer
     *       - Record that peer has seen this message
     *    c. Update hop count in database
     */
    @Override
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

        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "  EPIDEMIC ROUTING - MESH FORWARDING");
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "Messages to forward: " + messagesToForward.size());
        Log.d(TAG, "Connected devices: " + connectedDevices.size());

        int totalForwarded = 0;
        int totalSkipped = 0;

        // Process each message
        for (Message message : messagesToForward) {

            // Check hop count limit
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "⚠️ Message " + message.message_id + " reached max hops (" + MAX_HOPS + ")");
                totalSkipped++;
                continue;
            }

            // Check TTL
            if (message.ttl_timestamp < System.currentTimeMillis()) {
                Log.d(TAG, "⏰ Message " + message.message_id + " expired");
                totalSkipped++;
                continue;
            }

            Set<String> forwardedTo = new HashSet<>();
            int messageForwardCount = 0;

            // EPIDEMIC: Forward to ALL connected peers
            for (BluetoothDevice peer : connectedDevices) {
                try {
                    String peerId = DeviceIdentifier.getBluetoothDeviceId(peer);
                    String peerAddress = peer.getAddress();

                    // Skip if message is from this peer (don't send back to sender)
                    if (message.source_id != null &&
                            (message.source_id.equals(peerId) || message.source_id.equals(peerAddress))) {
                        Log.d(TAG, "⏭️ Skipping sender: " + peerId);
                        continue;
                    }

                    // Check if peer already has this message
                    if (hasPeerSeenMessage(peerId, message.message_id)) {
                        Log.d(TAG, "⏭️ Peer " + peerId + " already has message " +
                                message.message_id.substring(0, 8) + "...");
                        totalSkipped++;
                        continue;
                    }

                    // Skip if already forwarded to this peer in this batch
                    if (forwardedTo.contains(peerAddress)) {
                        continue;
                    }

                    // Create a copy of the message with incremented hop count
                    Message messageCopy = copyMessage(message);
                    messageCopy.hop_count++;

                    // Try to send via client threads first (direct connections)
                    boolean sent = false;
                    if (clientThreads != null) {
                        for (BluetoothClientThread client : clientThreads) {
                            if (client.getRemoteDeviceAddress().equals(peerAddress) &&
                                    client.isConnected()) {
                                client.write(messageCopy);
                                sent = true;
                                forwardedTo.add(peerAddress);
                                messageForwardCount++;
                                totalForwarded++;

                                Log.d(TAG, "✓ Forwarded " + message.message_id.substring(0, 8) +
                                        "... to " + peerId + " via client (hop " + messageCopy.hop_count + ")");
                                break;
                            }
                        }
                    }

                    // If not sent via client, try server broadcast
                    if (!sent && serverThread != null && serverThread.isAlive()) {
                        serverThread.sendToClient(peerAddress, messageCopy);
                        forwardedTo.add(peerAddress);
                        messageForwardCount++;
                        totalForwarded++;

                        Log.d(TAG, "✓ Forwarded " + message.message_id.substring(0, 8) +
                                "... to " + peerId + " via server (hop " + messageCopy.hop_count + ")");
                    }

                    // Record that peer has seen this message
                    if (forwardedTo.contains(peerAddress)) {
                        recordMessageForPeer(peerId, message.message_id);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error forwarding to peer", e);
                }
            }

            // Update hop count in database if message was forwarded
            if (!forwardedTo.isEmpty()) {
                message.hop_count++;
                dbExecutor.execute(() -> {
                    try {
                        messageDao.update(message);
                        Log.d(TAG, "✓ Updated hop count to " + message.hop_count +
                                " for message " + message.message_id.substring(0, 8) + "...");
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating message hop count", e);
                    }
                });

                Log.d(TAG, "📤 Message " + message.message_id.substring(0, 8) +
                        "... forwarded to " + messageForwardCount + " device(s)");
            }
        }

        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "  EPIDEMIC FORWARDING COMPLETE");
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "Total forwards: " + totalForwarded);
        Log.d(TAG, "Total skipped: " + totalSkipped);

        // Log to file for research analysis
        logger.logEvent(String.format(Locale.US,
                "EVENT=EPIDEMIC_MESH_FORWARD | MESSAGES=%d | PEERS=%d | FORWARDS=%d | SKIPPED=%d",
                messagesToForward.size(), connectedDevices.size(), totalForwarded, totalSkipped));
    }

    /**
     * Get all non-expired messages from database
     */
    @Override
    public List<Message> getMessagesToForward() {
        return messageDao.getNonExpiredMessages(System.currentTimeMillis());
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
     * Check if peer has seen this message (duplicate prevention)
     */
    private boolean hasPeerSeenMessage(String peerId, String messageId) {
        synchronized (historyLock) {
            Set<String> messages = peerMessageHistory.get(peerId);
            return messages != null && messages.contains(messageId);
        }
    }

    /**
     * Record that a message was forwarded to a peer
     */
    private void recordMessageForPeer(String peerId, String messageId) {
        synchronized (historyLock) {
            Set<String> messages = peerMessageHistory.computeIfAbsent(peerId, k -> new HashSet<>());

            // Prevent unbounded growth - limit messages per peer
            if (messages.size() >= MAX_MESSAGES_PER_PEER) {
                Log.w(TAG, "⚠️ Peer history full for " + peerId + ", clearing oldest entries");
                // Remove approximately 20% of entries
                int toRemove = MAX_MESSAGES_PER_PEER / 5;
                Iterator<String> it = messages.iterator();
                for (int i = 0; i < toRemove && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }

            messages.add(messageId);

            // Limit total number of peers tracked
            if (peerMessageHistory.size() > MAX_PEER_HISTORY) {
                // Remove the oldest peer entry
                Iterator<Map.Entry<String, Set<String>>> it = peerMessageHistory.entrySet().iterator();
                if (it.hasNext()) {
                    Map.Entry<String, Set<String>> oldest = it.next();
                    Log.d(TAG, "🗑️ Removing history for old peer: " + oldest.getKey());
                    it.remove();
                }
            }
        }
    }

    /**
     * Clear history for a disconnected peer (memory management)
     */
    public void clearPeerHistory(String peerId) {
        synchronized (historyLock) {
            Set<String> removed = peerMessageHistory.remove(peerId);
            if (removed != null) {
                Log.d(TAG, "🗑️ Cleared history for peer: " + peerId +
                        " (" + removed.size() + " messages)");
            }
        }
    }

    /**
     * Get statistics for research analysis
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();

        synchronized (historyLock) {
            stats.put("tracked_peers", peerMessageHistory.size());

            int totalMessages = 0;
            for (Set<String> messages : peerMessageHistory.values()) {
                totalMessages += messages.size();
            }
            stats.put("total_tracked_messages", totalMessages);

            int avgMessagesPerPeer = peerMessageHistory.isEmpty() ? 0 :
                    totalMessages / peerMessageHistory.size();
            stats.put("avg_messages_per_peer", avgMessagesPerPeer);
        }

        return stats;
    }

    /**
     * Shutdown and cleanup
     */
    @Override
    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            Log.d(TAG, "✓ Database executor shutdown");
        }

        synchronized (historyLock) {
            int peersTracked = peerMessageHistory.size();
            peerMessageHistory.clear();
            Log.d(TAG, "✓ Cleared history for " + peersTracked + " peers");
        }

        Log.d(TAG, "✓ EpidemicRouting shutdown complete");
    }
}