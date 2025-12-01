package com.example.dtn.routing;

import android.bluetooth.BluetoothDevice;
import android.net.wifi.p2p.WifiP2pDevice;

import com.example.dtn.model.data.Message;
import com.example.dtn.network.BluetoothClientThread;
import com.example.dtn.network.BluetoothServerThread;
import com.example.dtn.network.ClientThread;
import com.example.dtn.network.ServerThread;

import java.util.List;

public interface RoutingProtocol {
    void forwardMessagesToMultipleDevicesBluetooth(
            List<Message> messagesToForward,
            List<BluetoothDevice> connectedDevices,
            BluetoothServerThread serverThread,
            List<BluetoothClientThread> clientThreads
    );

    List<Message> getMessagesToForward();
    void shutdown();
}
