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
    private static final int INITIAL_COPIES = 8;
    private static final int MAX_HOPS = 15; // FIXED: Prevent infinite loops

    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    private final ExecutorService dbExecutor;

    public SprayAndWaitRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.dbExecutor = Executors.newSingleThreadExecutor();
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
            // FIXED: Use consistent device identifier
            if (message.source_id != null && message.source_id.equals(peerId)) {
                Log.d(TAG, "Skipping message from sender: " + message.message_id);
                continue;
            }

            // FIXED: Check hop count limit
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message reached max hops: " + message.message_id);
                continue;
            }

            if (message.copy_count > 1) {
                handleSprayPhase(message, peer, peerId, serverThread, clientThread);
            } else if (message.copy_count == 1) {
                handleWaitPhase(message, peer, peerId, serverThread, clientThread);
            } else {
                Log.w(TAG, "Invalid copy_count for message: " + message.message_id);
            }
        }
    }

    /**
     * FIXED: Spray phase with synchronized copy count update
     */
    private void handleSprayPhase(Message message, WifiP2pDevice peer, String peerId,
                                  ServerThread serverThread, ClientThread clientThread) {
        int copiesToGive = message.copy_count / 2;
        int copiesToKeep = message.copy_count - copiesToGive;

        Log.d(TAG, String.format(Locale.US, "SPRAY: Message %s - Keeping %d, Sending %d copies",
                message.message_id, copiesToKeep, copiesToGive));

        // FIXED: Update in-memory copy count immediately
        message.copy_count = copiesToKeep;

        // FIXED: Then update database asynchronously
        final String messageId = message.message_id;
        final int finalCopiesToKeep = copiesToKeep;
        dbExecutor.execute(() -> {
            try {
                Message dbMessage = messageDao.getMessageById(messageId);
                if (dbMessage != null) {
                    dbMessage.copy_count = finalCopiesToKeep;
                    messageDao.update(dbMessage);
                    Log.d(TAG, "Updated database copy_count to " + finalCopiesToKeep);
                }
            } catch (Exception e) {
                Log.e(TAG, "Database update failed for message: " + messageId, e);
            }
        });

        // Send copy to peer
        Message messageToSend = copyMessage(message);
        messageToSend.copy_count = copiesToGive;

        sendMessage(messageToSend, peer, peerId, serverThread, clientThread);
    }

    /**
     * FIXED: Wait phase - only forward to destination
     */
    private void handleWaitPhase(Message message, WifiP2pDevice peer, String peerId,
                                 ServerThread serverThread, ClientThread clientThread) {
        if (peerId.equals(message.destination_id) ||
                (peer.deviceName != null && peer.deviceName.equals(message.destination_id))) {

            Log.d(TAG, String.format(Locale.US, "WAIT: Forwarding message %s to destination %s",
                    message.message_id, peerId));
            sendMessage(message, peer, peerId, serverThread, clientThread);
        } else {
            Log.d(TAG, String.format(Locale.US, "WAIT: Holding message %s (destination: %s, peer: %s)",
                    message.message_id, message.destination_id, peerId));
        }
    }

    /**
     * FIXED: Send message with hop count check
     */
    private void sendMessage(Message message, WifiP2pDevice peer, String peerId,
                             ServerThread serverThread, ClientThread clientThread) {
        message.hop_count++;

        // FIXED: Check hop limit before sending
        if (message.hop_count > 15) {
            Log.w(TAG, "Message exceeded hop limit, not sending: " + message.message_id);
            return;
        }

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=SPRAY_AND_WAIT | MSG_ID=%s | FROM=%s | TO=%s | COPIES=%d | HOPS=%d",
                message.message_id, ownDeviceId, peerId, message.copy_count, message.hop_count));

        boolean sent = false;
        if (serverThread != null && serverThread.isConnected()) {
            serverThread.write(message);
            sent = true;
            Log.d(TAG, "Sent via ServerThread");
        } else if (clientThread != null && clientThread.isConnected()) {
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
     * Deep copy a Message
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

    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            Log.d(TAG, "Executor shutdown");
        }
    }
}
