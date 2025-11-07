package com.example.dtn.network;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

public class BluetoothClientThread extends Thread {
    private static final String TAG = "BluetoothClientThread";
    private static final UUID BT_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );

    private final BluetoothDevice device;
    private final Handler handler;
    private BluetoothSocket socket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private volatile boolean running = true;
    private boolean connected = false;
    public BluetoothClientThread(BluetoothDevice device, Handler handler) {
        this.device = device;
        this.handler = handler;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        Log.d(TAG, "ClientThread: Connecting to " + device.getName());

        int maxRetries = 5;
        int retryCount = 0;

        while (retryCount < maxRetries && !connected) {
            try {
                Log.d(TAG, "Attempt " + (retryCount + 1) + "/" + maxRetries);

                // Create socket
                socket = device.createRfcommSocketToServiceRecord(BT_UUID);
                Log.d(TAG, "✓ Socket created");

                // Try to connect with timeout
                socket.connect();
                Log.d(TAG, "✓ Socket connected!");
                connected = true;

                // Initialize streams
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();
                ois = new ObjectInputStream(socket.getInputStream());

                Log.d(TAG, "✓ Streams opened - Ready!");

                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    Log.d(TAG, "✓ UI Updated: Connected");
                    // The MainActivity should update its UI
                    // This will be received by the handler
                });

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

            } catch (IOException e) {
                retryCount++;
                Log.w(TAG, "Connection failed (attempt " + retryCount + "): " + e.getMessage());

                // Close failed socket
                try {
                    if (socket != null) socket.close();
                } catch (Exception ex) {
                    Log.e(TAG, "Close error", ex);
                }

                // Wait before retry
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Retrying in 2 seconds...");
                    try {
                        Thread.sleep(2000);  // Wait 2 seconds
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage());
                e.printStackTrace();
                break;
            } finally {
                if (!connected && retryCount >= maxRetries) {
                    Log.e(TAG, "Max retries reached, giving up");
                    close();
                }
            }
        }

        close();
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
        } catch (Exception e) {
            Log.e(TAG, "Close error", e);
        }
    }

    public static final int MESSAGE_READ = 1;
}
