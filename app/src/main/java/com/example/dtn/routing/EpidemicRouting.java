package com.example.dtn.routing;

import android.content.Context;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.Locale;


public class EpidemicRouting implements RoutingProtocol {

    private Logger logger;
    private String ownDeviceId;

    public EpidemicRouting(Context context, String ownDeviceId) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
    }

    @Override
    public void forwardMessages(List<Message> allMessages, ServerThread serverThread, ClientThread clientThread) {
        // Epidemic routing is simple: forward every message you have to the connected peer.
        // The peer's logic will discard duplicates it has already received.
        // A more optimized version would exchange message vectors first, but this captures the "flooding" spirit.
        for (Message message : allMessages) {

            // Increment hop count before forwarding
            message.hop_count++;

            // Log the forwarding event
            String logDetails = String.format(Locale.US,
                    "EVENT=MESSAGE_FORWARDED | MSG_ID=%s | FROM=%s | TO=PEER",
                    message.message_id, ownDeviceId);
            logger.logEvent(logDetails);

            // Send the message via the active connection thread
            if (serverThread != null) {
                serverThread.write(message);
            }
            if (clientThread != null) {
                clientThread.write(message);
            }
        }
    }
}