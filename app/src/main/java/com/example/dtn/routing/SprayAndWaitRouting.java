package com.example.dtn.routing;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.util.Log;

import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SprayAndWaitRouting implements RoutingProtocol {

    private static final String TAG = "SprayAndWaitRouting";
    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    private final ExecutorService dbExecutor;
    public static final int INITIAL_COPIES = 8;

    public SprayAndWaitRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.dbExecutor = Executors.newSingleThreadExecutor(); // Single thread for sequential DB operations
    }

    @Override
    public void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer,
                                ServerThread serverThread, ClientThread clientThread) {

        // Validate inputs
        if (peer == null || peer.deviceName == null) {
            Log.e(TAG, "Invalid peer device");
            return;
        }

        if (messagesToForward == null || messagesToForward.isEmpty()) {
            Log.d(TAG, "No messages to forward");
            return;
        }

        for (Message message : messagesToForward) {
            // Skip if message is from this peer (avoid forwarding back to sender)
            if (message.source_id != null && message.source_id.equals(peer.deviceName)) {
                Log.d(TAG, "Skipping message from sender: " + message.message_id);
                continue;
            }

            if (message.copy_count > 1) {
                // SPRAY PHASE: Split copies using binary splitting
                handleSprayPhase(message, peer, serverThread, clientThread);
            } else if (message.copy_count == 1) {
                // WAIT PHASE: Only forward to final destination
                handleWaitPhase(message, peer, serverThread, clientThread);
            } else {
                // Invalid copy count (0 or negative)
                Log.w(TAG, "Invalid copy_count for message: " + message.message_id);
            }
        }
    }

    /**
     * SPRAY PHASE: Binary split message copies between this node and peer
     */
    private void handleSprayPhase(Message message, WifiP2pDevice peer,
                                  ServerThread serverThread, ClientThread clientThread) {
        int copiesToGive = message.copy_count / 2;
        int copiesToKeep = message.copy_count - copiesToGive;

        Log.d(TAG, String.format(Locale.US, "SPRAY: Message %s - Keeping %d, Sending %d copies",
                message.message_id, copiesToKeep, copiesToGive));

        // Update local database copy count asynchronously
        final String messageId = message.message_id;
        dbExecutor.execute(() -> {
            try {
                Message localMessage = messageDao.getMessageById(messageId);
                if (localMessage != null) {
                    localMessage.copy_count = copiesToKeep;
                    messageDao.update(localMessage);
                    Log.d(TAG, "Updated local copy_count to " + copiesToKeep);
                }
            } catch (Exception e) {
                Log.e(TAG, "Database update failed for message: " + messageId, e);
            }
        });

        // Create a new message instance to send (don't modify the original)
        Message messageToSend = copyMessage(message);
        messageToSend.copy_count = copiesToGive;

        sendMessage(messageToSend, peer, serverThread, clientThread);
    }

    /**
     * WAIT PHASE: Only forward if peer is the final destination
     */
    private void handleWaitPhase(Message message, WifiP2pDevice peer,
                                 ServerThread serverThread, ClientThread clientThread) {
        if (peer.deviceName.equals(message.destination_id)) {
            Log.d(TAG, String.format(Locale.US, "WAIT: Forwarding message %s to destination %s",
                    message.message_id, peer.deviceName));
            sendMessage(message, peer, serverThread, clientThread);
        } else {
            Log.d(TAG, String.format(Locale.US, "WAIT: Holding message %s (destination: %s, peer: %s)",
                    message.message_id, message.destination_id, peer.deviceName));
        }
    }

    /**
     * Send message via the active connection (server OR client thread, not both)
     */
    private void sendMessage(Message message, WifiP2pDevice peer,
                             ServerThread serverThread, ClientThread clientThread) {
        // Increment hop count
        message.hop_count++;

        // Log forwarding event
        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=SPRAY_AND_WAIT | MSG_ID=%s | FROM=%s | TO=%s | COPIES=%d | HOPS=%d",
                message.message_id, ownDeviceId, peer.deviceName, message.copy_count, message.hop_count));

        // Send via whichever thread is active (NOT both)
        boolean sent = false;
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.write(message);
            sent = true;
            Log.d(TAG, "Sent via ServerThread");
        } else if (clientThread != null && clientThread.isAlive()) {
            clientThread.write(message);
            sent = true;
            Log.d(TAG, "Sent via ClientThread");
        }

        if (!sent) {
            Log.e(TAG, "Failed to send message - no active thread");
            logger.logEvent(String.format(Locale.US,
                    "EVENT=SEND_FAILED | MSG_ID=%s | REASON=NO_ACTIVE_THREAD", message.message_id));
        }
    }

    /**
     * Deep copy a Message object to avoid modifying the original
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
     * Shutdown the database executor when no longer needed
     * Call this from MainActivity.onDestroy()
     */
    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            Log.d(TAG, "Database executor shutdown");
        }
    }
}
