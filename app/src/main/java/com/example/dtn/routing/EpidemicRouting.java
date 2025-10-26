package com.example.dtn.routing;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.util.Log;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.Locale;

public class EpidemicRouting implements RoutingProtocol {

    private static final String TAG = "EpidemicRouting";
    private final Logger logger;
    private final String ownDeviceId;

    public EpidemicRouting(Context context, String ownDeviceId) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
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

        // Epidemic Routing: Forward ALL messages to encountered peer (flooding approach)
        for (Message message : messagesToForward) {
            // Skip if message is from this peer (avoid forwarding back to sender)
            if (message.source_id != null && message.source_id.equals(peer.deviceName)) {
                Log.d(TAG, "Skipping message from sender: " + message.message_id);
                continue;
            }

            forwardMessage(message, peer, serverThread, clientThread);
        }

        Log.d(TAG, String.format(Locale.US, "Epidemic: Forwarded %d messages to %s",
                messagesToForward.size(), peer.deviceName));
    }

    /**
     * Forward a single message via the active connection (server OR client, not both)
     * Epidemic routing uses unlimited replication - every peer gets a copy
     */
    private void forwardMessage(Message message, WifiP2pDevice peer,
                                ServerThread serverThread, ClientThread clientThread) {
        // Increment hop count
        message.hop_count++;

        // Log forwarding event with detailed information
        logger.logEvent(String.format(Locale.US,
                "EVENT=MESSAGE_FORWARDED | PROTOCOL=EPIDEMIC | MSG_ID=%s | FROM=%s | TO=%s | HOPS=%d | DEST=%s",
                message.message_id, ownDeviceId, peer.deviceName, message.hop_count, message.destination_id));

        // Send via whichever thread is active (NOT both)
        // You're either server OR client in a connection, never both simultaneously
        boolean sent = false;

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.write(message);
            sent = true;
            Log.d(TAG, String.format("Sent message %s via ServerThread", message.message_id));
        } else if (clientThread != null && clientThread.isAlive()) {
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
}
