package com.example.dtn.routing;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import com.example.dtn.utils.Logger;

import java.util.List;
import java.util.Locale;

public class EpidemicRouting implements RoutingProtocol {

    private final Logger logger;
    private final String ownDeviceId;

    public EpidemicRouting(Context context, String ownDeviceId) {
        this.logger = Logger.getInstance(context);
        this.ownDeviceId = ownDeviceId;
    }

    @Override
    public void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread) {
        for (Message message : messagesToForward) {
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
}
