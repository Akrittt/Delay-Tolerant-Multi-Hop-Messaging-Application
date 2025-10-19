package com.example.dtn.routing;

import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;

import java.util.List;

public interface RoutingProtocol {
    /**
     * The abstract method that all routing protocols must implement.
     * It now includes the connected peer to allow for more intelligent routing decisions.
     * @param messagesToForward The pre-filtered and prioritized list of messages.
     * @param peer The peer device we are currently connected to.
     * @param serverThread The active server thread, if any.
     * @param clientThread The active client thread, if any.
     */
    void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread);
}
