package com.example.dtn.routing;


import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;


public abstract class SprayAndWaitRouting implements RoutingProtocol {

    private Logger logger;
    private String ownDeviceId;
    private MessageDao messageDao;
    private ExecutorService executor;
    public static final int INITIAL_COPIES = 8;

    public SprayAndWaitRouting(Context context, String ownDeviceId, MessageDao messageDao, ExecutorService executor) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
        this.executor = executor;
    }

    @Override
    public void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread) {
        for (Message message : messagesToForward) {
            // Don't forward a message back to the device that sent it to you.
            if (message.source_id.equals(peer.deviceName)) {
                continue;
            }

            // --- SPRAY PHASE ---
            if (message.copy_count > 1) {
                int copiesToGive = message.copy_count / 2;
                int copiesToKeep = message.copy_count - copiesToGive;

                // Update the copy count on the message we are about to send
                message.copy_count = copiesToGive;

                // Update our own message's copy count in the database
                executor.execute(() -> {
                    Message localMessage = messageDao.getMessageById(message.message_id);
                    if (localMessage != null) {
                        localMessage.copy_count = copiesToKeep;
                        messageDao.update(localMessage);
                    }
                });

                sendMessage(message, peer, serverThread, clientThread);
            }
            // --- WAIT PHASE ---
            else if (message.copy_count == 1) {
                // In the "Wait" phase, we only forward directly to the final destination.
                if (peer.deviceName.equals(message.destination_id)) {
                    sendMessage(message, peer, serverThread, clientThread);
                }
            }
        }
    }

    private void sendMessage(Message message, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread) {
        message.hop_count++;
        logger.logEvent(String.format("EVENT=MESSAGE_FORWARDED | MSG_ID=%s | FROM=%s | TO=%s",
                message.message_id, ownDeviceId, peer.deviceName));

        if (serverThread != null) {
            serverThread.write(message);
        }
        if (clientThread != null) {
            clientThread.write(message);
        }
    }
}
