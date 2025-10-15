package com.example.dtn.network;

import android.os.Handler;
import android.util.Log;

import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;

//This thread runs on the client device and connects to the server's IP address.
public class ClientThread extends Thread {
    private Socket socket;
    private String hostAddress;
    private Handler handler;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    public static final int MESSAGE_READ = 1; // Same code as ServerThread

    public ClientThread(InetAddress hostAddress, Handler handler) {
        this.hostAddress = hostAddress.getHostAddress();
        this.handler = handler;
    }

    @Override
    public void run() {
        try {
            socket = new Socket(hostAddress, 8888);
            Log.d("ClientThread", "Connected to server.");
            oos = new ObjectOutputStream(socket.getOutputStream());
            ois = new ObjectInputStream(socket.getInputStream());

            // Loop to continuously listen for incoming messages
            while (socket != null) {
                try {
                    Message receivedMessage = (Message) ois.readObject();
                    if (receivedMessage != null) {
                        handler.obtainMessage(MESSAGE_READ, receivedMessage).sendToTarget();
                    }
                } catch (ClassNotFoundException e) {
                    Log.e("ClientThread", "ClassNotFoundException", e);
                }
            }
        } catch (IOException e) {
            Log.e("ClientThread", "IOException in run()", e);
        }
    }

    public void write(Message message) {
        new Thread(() -> {
            try {
                if (oos != null) {
                    oos.writeObject(message);
                    oos.flush();
                }
            } catch (IOException e) {
                Log.e("ClientThread", "IOException in write()", e);
            }
        }).start();
    }
}