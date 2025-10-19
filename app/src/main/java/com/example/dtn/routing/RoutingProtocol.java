package com.example.dtn.routing;

import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;
import android.net.wifi.p2p.WifiP2pDevice;

import java.util.List;


public interface RoutingProtocol {
    void forwardMessages(List<Message> allMessages, ServerThread serverThread, ClientThread clientThread);

    void forwardMessages(List<Message> messagesToForward, WifiP2pDevice peer, ServerThread serverThread, ClientThread clientThread);
}
