package com.example.dtn.network;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;
import androidx.annotation.RequiresPermission;
import com.example.dtn.data.Message;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BluetoothServerThread extends Thread {
    private static final String TAG = "BluetoothServerThread";
    private static final int MAX_CLIENTS = 7;
    private static final UUID BT_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );
    private static final String SERVICE_NAME = "DTN_BT_Service";

    private final BluetoothAdapter adapter;
    private final Handler handler;
    private BluetoothServerSocket serverSocket;
    private volatile boolean running = true;
    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_CONNECTION_ESTABLISHED = 2;
    public static final int MESSAGE_CONNECTION_LOST = 3;
    private List<ClientHandler> activeClients = Collections.synchronizedList(new ArrayList<>());

    public BluetoothServerThread(BluetoothAdapter adapter, Handler handler) {
        this.adapter = adapter;
        this.handler = handler;
        setName("BluetoothServerThread");
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, BT_UUID);
            Log.d(TAG, "✓ Server listening - accepting multiple clients");

            while (running) {
                try {
                    // Accept new client
                    BluetoothSocket clientSocket = serverSocket.accept();

                    if (clientSocket != null) {
                        String clientName = clientSocket.getRemoteDevice().getName();
                        String clientAddress = clientSocket.getRemoteDevice().getAddress();

                        Log.d(TAG, "✓ Client #" + (activeClients.size() + 1) + " connected: " + clientName);

                        // Check connection limit
                        if (activeClients.size() >= MAX_CLIENTS) {
                            Log.w(TAG, "Max clients reached, rejecting: " + clientName);
                            clientSocket.close();
                            continue;
                        }

                        // Create handler for this client
                        ClientHandler clientHandler = new ClientHandler(clientSocket, clientName, clientAddress);
                        activeClients.add(clientHandler);
                        clientHandler.start();

                        // Notify MainActivity
                        handler.obtainMessage(MESSAGE_CONNECTION_ESTABLISHED,
                                clientName + " (Total: " + activeClients.size() + ")").sendToTarget();
                    }

                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "Accept failed", e);
                    }
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Server creation failed", e);
        } finally {
            close();
        }
    }

    // Inner class to handle each client connection
    private class ClientHandler extends Thread {
        private final BluetoothSocket socket;
        private final String deviceName;
        private final String deviceAddress;
        private ObjectInputStream ois;
        private ObjectOutputStream oos;
        private volatile boolean clientRunning = true;

        public ClientHandler(BluetoothSocket socket, String name, String address) {
            this.socket = socket;
            this.deviceName = name;
            this.deviceAddress = address;
        }

        @Override
        public void run() {
            try {
                // Initialize streams
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();
                Thread.sleep(200);
                ois = new ObjectInputStream(socket.getInputStream());

                Log.d(TAG, "✓ Streams ready for: " + deviceName);
                // First, handle handshake
                Log.d(TAG, "Starting handshake with client...");

                // Send SERVER_READY
                oos.writeObject("SERVER_READY");
                oos.flush();
                Log.d(TAG, "✓ Server: Sent SERVER_READY");

                // Wait for CLIENT_READY
                Object clientResponse = ois.readObject();
                if (!"CLIENT_READY".equals(clientResponse)) {
                    throw new IOException("Invalid handshake from client: " + clientResponse);
                }
                Log.d(TAG, "✓ Server: Received CLIENT_READY");
                Log.d(TAG, "✓✓✓ Handshake complete - Connection established ✓✓✓");

                // Now enter message reading loop
                Log.d(TAG, "Entering message loop");

                // Message loop
                while (clientRunning && socket.isConnected()) {
                    try {
                        Object received = ois.readObject();

                        // Only process Message objects after handshake
                        if (received instanceof Message) {
                            Message message = (Message) received;
                            if (message != null) {
                                Log.d(TAG, "📨 From " + deviceName + ": " + message.message_id);
                                handler.obtainMessage(MESSAGE_READ, message).sendToTarget();
                            }
                        } else {
                            Log.w(TAG, "Received unexpected object type: " + received.getClass().getSimpleName());
                        }
                    } catch (ClassNotFoundException | IOException e) {
                        Log.e(TAG, "Error reading from " + deviceName, e);
                        break;
                    }
                }
            }catch (Exception e) {
                Log.e(TAG, "ClientHandler error for " + deviceName, e);
            } finally {
                closeClient();
            }
        }

        public void write(Message message) {
            if (oos != null && socket.isConnected()) {
                try {
                    synchronized (oos) {
                        oos.writeObject(message);
                        oos.flush();
                        oos.reset();
                    }
                    Log.d(TAG, "✓ Sent to " + deviceName + ": " + message.message_id);
                } catch (IOException e) {
                    Log.e(TAG, "Write error to " + deviceName, e);
                }
            }
        }

        private void closeClient() {
            clientRunning = false;
            try {
                if (ois != null) ois.close();
                if (oos != null) oos.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client", e);
            }

            activeClients.remove(this);
            Log.d(TAG, "✓ Client disconnected: " + deviceName + " (Remaining: " + activeClients.size() + ")");

            handler.obtainMessage(MESSAGE_CONNECTION_LOST,
                    deviceName + " (Remaining: " + activeClients.size() + ")").sendToTarget();
        }
    }

    // Broadcast message to ALL connected clients
    public void broadcastToAll(Message message) {
        Log.d(TAG, "Broadcasting to " + activeClients.size() + " clients");
        for (ClientHandler client : new ArrayList<>(activeClients)) {
            try {
                client.write(message);
            } catch (Exception e) {
                Log.e(TAG, "Error sending to client", e);
            }
        }
    }

    public void close() {
        running = false;

        // Close all client connections
        for (ClientHandler client : new ArrayList<>(activeClients)) {
            client.closeClient();
        }
        activeClients.clear();

        // Close server socket
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server", e);
            }
        }

        Log.d(TAG, "✓ Server closed");
    }
    public String getFirstClientAddress() {
        synchronized (activeClients) {
            if (!activeClients.isEmpty()) {
                ClientHandler firstClient = activeClients.get(0);
                if (firstClient != null && firstClient.deviceAddress != null) {
                    return firstClient.deviceAddress;
                }
            }
        }
        return null;
    }

    // Get count of connected clients
    public int getConnectedClientCount() {
        synchronized (activeClients) {
            return activeClients.size();
        }
    }

    /**
     * Send message to specific client by address
     * Used for mesh routing
     */
    public void sendToClient(String deviceAddress, Message message) {
        if (deviceAddress == null || message == null) {
            Log.w(TAG, "Invalid parameters for sendToClient");
            return;
        }

        synchronized (activeClients) {
            for (ClientHandler client : activeClients) {
                if (client.deviceAddress.equals(deviceAddress)) {
                    client.write(message);
                    Log.d(TAG, "Sent message to specific client: " + client.deviceName);
                    return;
                }
            }
        }

        Log.w(TAG, "Client not found: " + deviceAddress);
    }

    /**
     * Get list of connected client addresses
     * Used for mesh management
     */
    public List<String> getConnectedClientAddresses() {
        List<String> addresses = new ArrayList<>();
        synchronized (activeClients) {
            for (ClientHandler client : activeClients) {
                addresses.add(client.deviceAddress);
            }
        }
        return addresses;
    }
}