package com.example.dtn.routing;

import com.example.dtn.data.Message;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;

import java.util.List;


public interface RoutingProtocol {
    void forwardMessages(List<Message> allMessages, ServerThread serverThread, ClientThread clientThread);
}
