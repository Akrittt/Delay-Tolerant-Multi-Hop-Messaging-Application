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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SprayAndWaitRouting - Copy-limited DTN routing protocol
 *
 * ALGORITHM:
 * - SPRAY PHASE: Distribute limited copies of message across network
 * - WAIT PHASE: Hold message until destination is encountered
 * - Lower overhead than Epidemic (controlled replication)
 *
 * COPY DISTRIBUTION:
 * - Initial copies: 6 (configurable)
 * - Binary spray: Split copies evenly when encountering peers
 * - Example: 6 copies → give 3 to peer, keep 3
 *            3 copies → give 1 to peer, keep 2
 *            2 copies → give 1 to peer, keep 1
 *            1 copy  → WAIT mode (only send to destination)
 *
 * IMPLEMENTATION:
 * - Uses Bluetooth mesh topology
 * - Updates copy_count in database after each transmission
 * - Tracks hop count separately from copy count
 * - Respects hop limit (max 15 hops)
 *
 * RESEARCH METRICS:
 * - Delivery ratio: Medium-High (70-90% in connected networks)
 * - Latency: Medium (slower than Epidemic due to limited copies)
 * - Overhead: Low-Medium (controlled by initial copy count)
 * - Scalability: Good (predictable bandwidth usage)
 *
 * @author DTN Messenger Team
 * @version 2.0
 */
public class SprayAndWaitRouting implements RoutingProtocol {

    private static final String TAG = "SprayAndWaitRouting";

    // Protocol parameters
    private static final int INITIAL_COPIES = 6;  // Starting copy count for new messages
    private static final int MAX_HOPS = 15;

    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    private final ExecutorService dbExecutor;

    // Statistics tracking
    private int totalSprayed = 0;
    private int totalWaited = 0;

    /**
     * Constructor
     *
     * @param context Application context for logger
     * @param ownDeviceId This device's unique identifier
     * @param messageDao Database access object for message operations
     */
    public SprayAndWaitRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.dbExecutor = Executors.newSingleThreadExecutor();

        Log.d(TAG, "✓ SprayAndWaitRouting initialized for device: " + ownDeviceId);
        Log.d(TAG, "  Initial copies per message: " + INITIAL_COPIES);
    }

    /**
     * Forward messages to multiple Bluetooth devices (mesh routing)
     *
     * SPRAY-AND-WAIT ALGORITHM:
     * 1. For each message:
     *    a. Check if message expired or exceeded hop limit
     *    b. If copy_count > 1: SPRAY PHASE
     *       - Calculate copies to distribute to each peer
     *       - Binary spray: split remaining copies
     *       - Update own copy_count in database
     *    c. If copy_count == 1: WAIT PHASE
     *       - Only forward to destination device
     *    d. Update hop count after transmission
     * 2. Log forwarding statistics
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
        Log.d(TAG, "  SPRAY-AND-WAIT - MESH FORWARDING");
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "Messages to forward: " + messagesToForward.size());
        Log.d(TAG, "Connected devices: " + connectedDevices.size());

        int totalForwarded = 0;
        int totalSkipped = 0;
        int sprayPhaseCount = 0;
        int waitPhaseCount = 0;

        for (Message message : messagesToForward) {

            // Check hop count limit
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "⚠️ Message " + message.message_id.substring(0, 8) +
                        "... reached max hops (" + MAX_HOPS + ")");
                totalSkipped++;
                continue;
            }

            // Check TTL
            if (message.ttl_timestamp < System.currentTimeMillis()) {
                Log.d(TAG, "⏰ Message " + message.message_id.substring(0, 8) + "... expired");
                totalSkipped++;
                continue;
            }

            // ═══ SPRAY PHASE: copy_count > 1 ═══
            if (message.copy_count > 1) {
                int forwarded = handleSprayPhase(message, connectedDevices, serverThread, clientThreads);
                totalForwarded += forwarded;
                if (forwarded > 0) {
                    sprayPhaseCount++;
                }
            }
            // ═══ WAIT PHASE: copy_count == 1 ═══
            else if (message.copy_count == 1) {
                int forwarded = handleWaitPhase(message, connectedDevices, serverThread, clientThreads);
                totalForwarded += forwarded;
                if (forwarded > 0) {
                    waitPhaseCount++;
                }
            }
            // Invalid copy count
            else {
                Log.w(TAG, "⚠️ Invalid copy_count (" + message.copy_count + ") for message " +
                        message.message_id.substring(0, 8) + "...");
                totalSkipped++;
            }
        }

        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "  SPRAY-AND-WAIT COMPLETE");
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "Total forwards: " + totalForwarded);
        Log.d(TAG, "Spray phase messages: " + sprayPhaseCount);
        Log.d(TAG, "Wait phase messages: " + waitPhaseCount);
        Log.d(TAG, "Total skipped: " + totalSkipped);

        // Update statistics
        totalSprayed += sprayPhaseCount;
        totalWaited += waitPhaseCount;

        // Log to file for research analysis
        logger.logEvent(String.format(Locale.US,
                "EVENT=SPRAY_AND_WAIT_FORWARD | MESSAGES=%d | PEERS=%d | FORWARDS=%d | " +
                        "SPRAY=%d | WAIT=%d | SKIPPED=%d",
                messagesToForward.size(), connectedDevices.size(), totalForwarded,
                sprayPhaseCount, waitPhaseCount, totalSkipped));
    }

    /**
     * SPRAY PHASE: Distribute copies across multiple peers
     *
     * Binary Spray Algorithm:
     * - Calculate copies per peer: remaining_copies / (num_peers + 1)
     * - Distribute copies evenly
     * - Update local copy_count
     * - Update database asynchronously
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private int handleSprayPhase(Message message, List<BluetoothDevice> connectedDevices,
                                 BluetoothServerThread serverThread,
                                 List<BluetoothClientThread> clientThreads) {

        int availablePeers = connectedDevices.size();
        int copiesPerPeer = Math.max(1, message.copy_count / (availablePeers + 1));
        int remainingCopies = message.copy_count;
        int forwardedCount = 0;

        Log.d(TAG, "📤 SPRAY: Message " + message.message_id.substring(0, 8) +
                "... has " + message.copy_count + " copies for " + availablePeers + " peer(s)");
        Log.d(TAG, "   Strategy: Give " + copiesPerPeer + " copies per peer");

        for (BluetoothDevice peer : connectedDevices) {
            if (remainingCopies <= 1) {
                Log.d(TAG, "   Keeping last copy (entering WAIT mode)");
                break; // Keep at least 1 copy
            }

            try {
                String peerId = DeviceIdentifier.getBluetoothDeviceId(peer);
                String peerAddress = peer.getAddress();

                // Skip if message is from this peer
                if (message.source_id != null &&
                        (message.source_id.equals(peerId) || message.source_id.equals(peerAddress))) {
                    Log.d(TAG, "   ⏭️ Skipping sender: " + peerId);
                    continue;
                }

                // Calculate copies to give
                int copiesToGive = Math.min(copiesPerPeer, remainingCopies - 1);

                if (copiesToGive < 1) {
                    break;
                }

                // Create message copy with allocated copy count
                Message messageCopy = copyMessage(message);
                messageCopy.copy_count = copiesToGive;
                messageCopy.hop_count++;

                // Try to send
                boolean sent = false;

                // Try client threads first (direct connections)
                if (clientThreads != null) {
                    for (BluetoothClientThread client : clientThreads) {
                        if (client.getRemoteDeviceAddress().equals(peerAddress) &&
                                client.isConnected()) {
                            client.write(messageCopy);
                            sent = true;
                            break;
                        }
                    }
                }

                // Try server broadcast if client failed
                if (!sent && serverThread != null && serverThread.isAlive()) {
                    serverThread.sendToClient(peerAddress, messageCopy);
                    sent = true;
                }

                if (sent) {
                    remainingCopies -= copiesToGive;
                    forwardedCount++;

                    Log.d(TAG, "   ✓ Gave " + copiesToGive + " copies to " + peerId +
                            " (Remaining: " + remainingCopies + ")");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error during spray to peer", e);
            }
        }

        // Update own copy count in database
        final int finalCopies = remainingCopies;
        message.copy_count = finalCopies;
        message.hop_count++;

        dbExecutor.execute(() -> {
            try {
                messageDao.update(message);
                Log.d(TAG, "   ✓ Database updated: keeping " + finalCopies + " copies, hop " +
                        message.hop_count);
            } catch (Exception e) {
                Log.e(TAG, "Error updating message in spray phase", e);
            }
        });

        return forwardedCount;
    }

    /**
     * WAIT PHASE: Only forward to destination device
     *
     * Once copy_count reaches 1, we stop spraying and wait for the destination.
     * Message is only forwarded if destination device is encountered.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private int handleWaitPhase(Message message, List<BluetoothDevice> connectedDevices,
                                BluetoothServerThread serverThread,
                                List<BluetoothClientThread> clientThreads) {

        Log.d(TAG, "⏳ WAIT: Message " + message.message_id.substring(0, 8) +
                "... waiting for destination: " + message.destination_id);

        for (BluetoothDevice peer : connectedDevices) {
            try {
                String peerId = DeviceIdentifier.getBluetoothDeviceId(peer);
                String peerAddress = peer.getAddress();

                // Only forward if this peer is the destination
                if (peerId.equals(message.destination_id) || peerAddress.equals(message.destination_id)) {

                    Message messageCopy = copyMessage(message);
                    messageCopy.hop_count++;

                    boolean sent = false;

                    // Try client threads
                    if (clientThreads != null) {
                        for (BluetoothClientThread client : clientThreads) {
                            if (client.getRemoteDeviceAddress().equals(peerAddress) &&
                                    client.isConnected()) {
                                client.write(messageCopy);
                                sent = true;
                                break;
                            }
                        }
                    }

                    // Try server
                    if (!sent && serverThread != null && serverThread.isAlive()) {
                        serverThread.sendToClient(peerAddress, messageCopy);
                        sent = true;
                    }

                    if (sent) {
                        Log.d(TAG, "   ✓ Forwarded to DESTINATION " + peerId +
                                " (hop " + messageCopy.hop_count + ")");

                        // Update hop count in database
                        message.hop_count++;
                        dbExecutor.execute(() -> {
                            try {
                                messageDao.update(message);
                            } catch (Exception e) {
                                Log.e(TAG, "Error updating message in wait phase", e);
                            }
                        });

                        return 1; // Successfully forwarded to destination
                    }

                    break; // Destination found (whether sent or not)
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during wait phase", e);
            }
        }

        Log.d(TAG, "   ⏳ Destination not found, holding message");
        return 0;
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
     * Get statistics for research analysis
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total_sprayed", totalSprayed);
        stats.put("total_waited", totalWaited);
        stats.put("initial_copies", INITIAL_COPIES);
        return stats;
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        totalSprayed = 0;
        totalWaited = 0;
        Log.d(TAG, "✓ Statistics reset");
    }

    /**
     * Shutdown and cleanup
     */
    @Override
    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()){
            dbExecutor.shutdown();
            Log.d(TAG, "✓ Database Executor shutdown");
        }
        Log.d(TAG, "✓ SprayAndWaitRouting shutdown complete");
        Log.d(TAG, "  Final stats - Sprayed: " + totalSprayed + ", Waited: " + totalWaited);
    }
}
