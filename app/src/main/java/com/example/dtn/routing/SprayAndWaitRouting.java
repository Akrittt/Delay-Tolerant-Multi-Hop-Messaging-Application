package com.example.dtn.routing;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.data.Message;
import com.example.dtn.data.MessageDao;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.Locale;

public class SprayAndWaitRouting implements RoutingProtocol {

    private final Logger logger;
    private final String ownDeviceId;
    private final MessageDao messageDao;
    public static final int INITIAL_COPIES = 8;

    public SprayAndWaitRouting(Context context, String ownDeviceId, MessageDao messageDao) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
        this.messageDao = messageDao;
    }

    @Override
    public void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread) {
        for (Message message : messagesToForward) {
            if (message.source_id.equals(peer.deviceName)) {
                continue;
            }

            if (message.copy_count > 1) { // SPRAY PHASE
                int copiesToGive = message.copy_count / 2;
                int copiesToKeep = message.copy_count - copiesToGive;

                Message localMessage = messageDao.getMessageById(message.message_id);
                if (localMessage != null) {
                    localMessage.copy_count = copiesToKeep;
                    messageDao.update(localMessage);

                    localMessage.copy_count = copiesToGive;
                    sendMessage(localMessage, peer, serverThread, clientThread);
                }
            } else if (message.copy_count == 1) { // WAIT PHASE
                if (peer.deviceName.equals(message.destination_id)) {
                    sendMessage(message, peer, serverThread, clientThread);
                }
            }
        }
    }

    private void sendMessage(Message message, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread) {
        message.hop_count++;
        logger.logEvent(String.format(Locale.US, "EVENT=MESSAGE_FORWARDED | MSG_ID=%s | FROM=%s | TO=%s",
                message.message_id, ownDeviceId, peer.deviceName));

        if (serverThread != null) {
            serverThread.write(message);
        }
        if (clientThread != null) {
            clientThread.write(message);
        }
    }
}
