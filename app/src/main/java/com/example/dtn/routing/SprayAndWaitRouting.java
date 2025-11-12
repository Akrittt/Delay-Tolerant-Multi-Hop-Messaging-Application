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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SprayAndWaitRouting implements RoutingProtocol {

    private static final String TAG = "SprayAndWaitRouting";
    private static final int INITIAL_COPIES = 8;
    private static final int MAX_HOPS = 15;

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
            //  Use consistent device identifier
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

        String peerId = peer.getName();

        for (Message message : messagesToForward) {
            if (message.source_id != null && message.source_id.equals(peerId)) {
                Log.d(TAG, "Skipping message from sender: " + message.message_id);
                continue;
            }

            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message reached max hops: " + message.message_id);
                continue;
            }

            if (message.copy_count > 1) {
                handleSprayPhaseBluetooth(message, peer, peerId, serverThread, clientThread);
            } else if (message.copy_count == 1) {
                handleWaitPhaseBluetooth(message, peer, peerId, serverThread, clientThread);
            } else {
                Log.w(TAG, "Invalid copy_count for message: " + message.message_id);
            }
        }
    }

    /**
     * Spray phase with synchronized copy count update
     */
    private void handleSprayPhase(Message message, WifiP2pDevice peer, String peerId,
                                  ServerThread serverThread, ClientThread clientThread) {
        int copiesToGive = message.copy_count / 2;
        int copiesToKeep = message.copy_count - copiesToGive;

        Log.d(TAG, String.format(Locale.US, "SPRAY: Message %s - Keeping %d, Sending %d copies",
                message.message_id, copiesToKeep, copiesToGive));

        //  Update in-memory copy count immediately
        message.copy_count = copiesToKeep;

        // Then update database asynchronously
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

    private void handleSprayPhaseBluetooth(Message message, BluetoothDevice peer, String peerId,
                                           BluetoothServerThread serverThread,
                                           BluetoothClientThread clientThread) {
        int copiesToGive = message.copy_count / 2;
        int copiesToKeep = message.copy_count - copiesToGive;

        Log.d(TAG, String.format(Locale.US, "SPRAY (Bluetooth): Message %s - Keeping %d, Sending %d copies",
                message.message_id, copiesToKeep, copiesToGive));

        message.copy_count = copiesToKeep;

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

        Message messageToSend = copyMessage(message);
        messageToSend.copy_count = copiesToGive;

        sendMessageBluetooth(messageToSend, peer, peerId, serverThread, clientThread);
    }

    /**
     * Wait phase - only forward to destination
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
    private void handleWaitPhaseBluetooth(Message message, BluetoothDevice peer, String peerId,
                                          BluetoothServerThread serverThread,
                                          BluetoothClientThread clientThread) {
        if (peerId.equals(message.destination_id)) {
            Log.d(TAG, String.format(Locale.US, "WAIT (Bluetooth): Forwarding message %s to destination %s",
                    message.message_id, peerId));
            sendMessageBluetooth(message, peer, peerId, serverThread, clientThread);
        } else {
            Log.d(TAG, String.format(Locale.US, "WAIT (Bluetooth): Holding message %s (destination: %s)",
                    message.message_id, message.destination_id));
        }
    }

    /**
     * Send message with hop count check
     */
    private void sendMessage(Message message, WifiP2pDevice peer, String peerId,
                             ServerThread serverThread, ClientThread clientThread) {
        message.hop_count++;

        // Check hop limit before sending
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

    private void sendMessageBluetooth(Message message, BluetoothDevice peer, String peerId,
                                      BluetoothServerThread serverThread,
                                      BluetoothClientThread clientThread) {
        message.hop_count++;

        if (message.hop_count > MAX_HOPS) {
            Log.w(TAG, "Message exceeded hop limit, not sending: " + message.message_id);
            return;
        }

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=SPRAY_AND_WAIT | TRANSPORT=BLUETOOTH | MSG_ID=%s | FROM=%s | TO=%s | COPIES=%d | HOPS=%d",
                message.message_id, ownDeviceId, peerId, message.copy_count, message.hop_count));

        boolean sent = false;
        if (clientThread != null && clientThread.isAlive()) {
            clientThread.write(message);
            sent = true;
            Log.d(TAG, "Sent via Bluetooth ClientThread");
        } else if (serverThread != null && serverThread.isAlive()) {
            serverThread.broadcastToAll(message);
            sent = true;
            Log.d(TAG, "Sent via Bluetooth ServerThread");
        }

        if (!sent) {
            Log.e(TAG, "Failed to send message - no active Bluetooth thread");
            logger.logEvent(String.format(Locale.US,
                    "EVENT=SEND_FAILED | TRANSPORT=BLUETOOTH | MSG_ID=%s | REASON=NO_ACTIVE_THREAD", message.message_id));
        }
    }

    /**
     * ✅ NEW METHOD: Mesh-aware Spray-and-Wait forwarding
     * Distributes copies across multiple connected peers simultaneously
     */
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

        Log.d(TAG, "=== MESH SPRAY-AND-WAIT START ===");
        Log.d(TAG, "Messages to forward: " + messagesToForward.size());
        Log.d(TAG, "Connected devices: " + connectedDevices.size());

        int totalForwarded = 0;

        for (Message message : messagesToForward) {

            // Check hop count
            if (message.hop_count >= MAX_HOPS) {
                Log.d(TAG, "Message " + message.message_id + " reached max hops");
                continue;
            }

            // SPRAY PHASE: Distribute copies across peers
            if (message.copy_count > 1) {
                int availablePeers = connectedDevices.size();
                int copiesPerPeer = Math.max(1, message.copy_count / (availablePeers + 1));
                int remainingCopies = message.copy_count;

                Log.d(TAG, "SPRAY: Message " + message.message_id +
                        " - " + message.copy_count + " copies across " + availablePeers + " peers");

                for (BluetoothDevice peer : connectedDevices) {
                    if (remainingCopies <= 1) {
                        break; // Keep at least 1 copy
                    }

                    try {
                        String peerId = peer.getName();
                        String peerAddress = peer.getAddress();

                        // Skip if message is from this peer
                        if (message.source_id != null &&
                                (message.source_id.equals(peerId) || message.source_id.equals(peerAddress))) {
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

                        if (!sent && serverThread != null && serverThread.isAlive()) {
                            serverThread.sendToClient(peerAddress, messageCopy);
                            sent = true;
                        }

                        if (sent) {
                            remainingCopies -= copiesToGive;
                            totalForwarded++;

                            Log.d(TAG, "✓ SPRAY: Sent " + copiesToGive + " copies to " +
                                    peerId + " (Remaining: " + remainingCopies + ")");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error during spray to peer", e);
                    }
                }

                // Update own copy count
                message.copy_count = remainingCopies;
                try {
                    messageDao.update(message);
                    Log.d(TAG, "Updated database: keeping " + remainingCopies + " copies");
                } catch (Exception e) {
                    Log.e(TAG, "Error updating message", e);
                }

            }
            // WAIT PHASE: Forward only to destination
            else if (message.copy_count == 1) {
                for (BluetoothDevice peer : connectedDevices) {
                    try {
                        String peerId = peer.getName();
                        String peerAddress = peer.getAddress();

                        // Only forward if this peer is the destination
                        if (peerId.equals(message.destination_id) ||
                                peerAddress.equals(message.destination_id)) {

                            Message messageCopy = copyMessage(message);
                            messageCopy.hop_count++;

                            boolean sent = false;

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

                            if (!sent && serverThread != null && serverThread.isAlive()) {
                                serverThread.sendToClient(peerAddress, messageCopy);
                                sent = true;
                            }

                            if (sent) {
                                totalForwarded++;
                                Log.d(TAG, "✓ WAIT: Forwarded " + message.message_id +
                                        " to destination " + peerId);
                            }

                            break; // Only send to destination
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during wait phase", e);
                    }
                }
            }
        }

        Log.d(TAG, "=== MESH SPRAY-AND-WAIT COMPLETE ===");
        Log.d(TAG, "Total forwards: " + totalForwarded);

        logger.logEvent(String.format(Locale.US,
                "EVENT=MESH_FORWARDING | PROTOCOL=SPRAY_AND_WAIT | MESSAGES=%d | FORWARDS=%d | PEERS=%d",
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

    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            Log.d(TAG, "Executor shutdown");
        }
    }
}
