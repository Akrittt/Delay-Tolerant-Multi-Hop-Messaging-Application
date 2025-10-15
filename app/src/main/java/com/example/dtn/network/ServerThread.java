package com.example.dtn.network;


import android.os.Handler;
import android.util.Log;

import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;


//This thread runs on the device that
// acts as the "group owner" in a Wi-Fi Direct group. It waits for a client to connect.
public class ServerThread extends Thread {
    private ServerSocket serverSocket;
    private Socket socket;
    private Handler handler;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    public static final int MESSAGE_READ = 1; // A code to identify received messages

    public ServerThread(Handler handler) {
        this.handler = handler;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(8888);
            Log.d("ServerThread", "Server started, waiting for client...");
            socket = serverSocket.accept(); // Blocks until a client connects
            Log.d("ServerThread", "Client connected.");

            oos = new ObjectOutputStream(socket.getOutputStream());
            ois = new ObjectInputStream(socket.getInputStream());

            // Loop to continuously listen for incoming messages
            while (socket != null) {
                try {
                    Message receivedMessage = (Message) ois.readObject();
                    if (receivedMessage != null) {
                        // Send the received message back to the MainActivity UI thread
                        handler.obtainMessage(MESSAGE_READ, receivedMessage).sendToTarget();
                    }
                } catch (ClassNotFoundException e) {
                    Log.e("ServerThread", "ClassNotFoundException", e);
                }
            }
        } catch (IOException e) {
            Log.e("ServerThread", "IOException in run()", e);
        }
    }

    public void write(Message message) {
        // Run in a separate thread to avoid blocking
        new Thread(() -> {
            try {
                if (oos != null) {
                    oos.writeObject(message);
                    oos.flush();
                }
            } catch (IOException e) {
                Log.e("ServerThread", "IOException in write()", e);
            }
        }).start();
    }
}
