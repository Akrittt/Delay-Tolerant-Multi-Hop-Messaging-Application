package com.example.dtn.network;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.data.Message;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

public class BluetoothServerThread extends Thread {
    private static final String TAG = "BluetoothServerThread";
    private static final UUID BT_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );
    private static final String SERVICE_NAME = "DTN_BT_Service";

    private final BluetoothAdapter adapter;
    private final Handler handler;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private volatile boolean running = true;

    public BluetoothServerThread(BluetoothAdapter adapter, Handler handler) {
        this.adapter = adapter;
        this.handler = handler;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        try {
            Log.d(TAG, "Starting Bluetooth server...");

            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, BT_UUID);
            Log.d(TAG, "✓ Bluetooth server listening on " + SERVICE_NAME);

            // Accept incoming connection
            socket = serverSocket.accept();
            Log.d(TAG, "✓ Client connected: " + socket.getRemoteDevice().getName());

            // Initialize streams
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(socket.getInputStream());

            // Read messages
            while (running && socket.isConnected()) {
                try {
                    Message message = (Message) ois.readObject();
                    if (message != null) {
                        Log.d(TAG, "Received: " + message.message_id);
                        handler.obtainMessage(MESSAGE_READ, message).sendToTarget();
                    }
                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "ClassNotFoundException", e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Server error", e);
        } finally {
            close();
        }
    }

    public void write(Message message) {
        try {
            synchronized (oos) {
                oos.writeObject(message);
                oos.flush();
                oos.reset();
            }
            Log.d(TAG, "Sent: " + message.message_id);
        } catch (Exception e) {
            Log.e(TAG, "Write error", e);
        }
    }

    public void close() {
        running = false;
        try {
            if (ois != null) ois.close();
            if (oos != null) oos.close();
            if (socket != null) socket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Close error", e);
        }
    }

    public static final int MESSAGE_READ = 1;
}
