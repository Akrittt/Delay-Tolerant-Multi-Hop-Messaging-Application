package com.example.dtn.network;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import com.example.dtn.view.MainActivity;
import com.example.dtn.model.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BluetoothClientThread extends Thread {
    private static final String TAG = "BluetoothClientThread";
    private static final int MESSAGE_CONNECTION_LOST = 3;
    public static final int MESSAGE_READ = 1;
    private static final UUID BT_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );
    private final Context context;

    private final BluetoothDevice device;
    private final Handler handler;
    private BluetoothSocket socket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private volatile boolean running = true;
    private volatile boolean connected = false;

    // Track device info
    private final String deviceAddress;
    private final String deviceName;

    public BluetoothClientThread(BluetoothDevice device, Handler handler, Context context) {
        this.device = device;
        this.handler = handler;
        this.deviceAddress = device.getAddress();
        this.context = context;

        // Safe device name retrieval
        String name = "Unknown";
        try {
            name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "Device_" + deviceAddress.replace(":", "");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot get device name");
        }
        this.deviceName = name;

        setName("BTClient-" + deviceName);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        Log.d(TAG, "ClientThread: Connecting to " + deviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "✗✗✗ BLUETOOTH_CONNECT permission denied!");
                if (handler != null) {
                    android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                    msg.obj = deviceAddress;
                    handler.sendMessage(msg);
                }
                return;
            }
        }

        int maxRetries = 5;
        int retryCount = 0;

        while (retryCount < maxRetries && !connected && running) {
            try {
                retryCount++;
                Log.d(TAG, "Connection attempt " + retryCount + "/" + maxRetries + " to " + deviceName);

                // Create socket
                try {
                    socket = device.createRfcommSocketToServiceRecord(BT_UUID);
                    Log.d(TAG, "✓ Socket created for " + deviceName);

                } catch (SecurityException e) {
                    Log.e(TAG, "✗✗✗ SecurityException: BLUETOOTH_CONNECT permission denied!", e);
                    if (handler != null) {
                        // Send device address in message
                        android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                        msg.obj = deviceAddress;
                        handler.sendMessage(msg);
                    }
                    return;
                }

                // Try to connect
                try {
                    socket.connect();
                    Log.d(TAG, "✓ Socket connected to " + deviceName);
                    connected = true;

                } catch (SecurityException e) {
                    Log.e(TAG, "✗ SecurityException during connect", e);
                    throw new IOException("Permission denied during connect");
                }

                // Wait for server to initialize first
                Log.d(TAG, "Waiting for server to initialize streams...");
                Thread.sleep(500);

                Log.d(TAG, "Initializing client streams for " + deviceName);

                // Client creates streams second
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();
                Log.d(TAG, "✓ Client: OutputStream created and flushed for " + deviceName);

                Thread.sleep(200);

                ois = new ObjectInputStream(socket.getInputStream());
                Log.d(TAG, "✓ Client: InputStream created for " + deviceName);

                // Handshake protocol
                Log.d(TAG, "Starting handshake with server " + deviceName);

                // Wait for server ready signal
                Object serverResponse = ois.readObject();

                if (!"SERVER_READY".equals(serverResponse)) {
                    throw new IOException("Invalid handshake from server: " + serverResponse);
                }

                Log.d(TAG, "✓ Client: Received SERVER_READY from " + deviceName);

                // Send acknowledgment
                oos.writeObject("CLIENT_READY");
                oos.flush();
                Log.d(TAG, "✓ Client: Sent CLIENT_READY to " + deviceName);

                Log.d(TAG, "✓✓✓ Handshake complete with " + deviceName + " ✓✓✓");

                // Enter message reading loop
                Log.d(TAG, "Entering message loop for " + deviceName);

                while (running && socket.isConnected()) {
                    try {
                        Message message = (Message) ois.readObject();
                        if (message != null) {
                            Log.d(TAG, "📨 Received from " + deviceName + ": " + message.message_id);
                            handler.obtainMessage(MESSAGE_READ, message).sendToTarget();
                        }
                    } catch (ClassNotFoundException e) {
                        Log.e(TAG, "ClassNotFoundException from " + deviceName, e);
                        break;
                    } catch (IOException e) {
                        Log.e(TAG, "Connection lost to " + deviceName + ": " + e.getMessage());
                        break;
                    }
                }

                // If we exit loop, connection is lost
                Log.w(TAG, "Exited message loop - connection lost to " + deviceName);
                // Send device address in connection lost message
                if (handler != null) {
                    android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                    msg.obj = deviceAddress;
                    handler.sendMessage(msg);
                }
                connected = false;

                // Connection succeeded, break retry loop
                break;

            } catch (InterruptedException e) {
                Log.e(TAG, "Thread interrupted during initialization", e);
                Thread.currentThread().interrupt();
                break;

            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Handshake error - unexpected object type", e);
                retryCount++;
                connected = false;
                cleanupSocket();
                if (retryCount < maxRetries) {
                    waitBeforeRetry(retryCount);
                }

            } catch (IOException e) {
                connected = false;
                Log.w(TAG, "Connection failed to " + deviceName + " (attempt " + retryCount + "): " + e.getMessage());
                cleanupSocket();

                if (retryCount < maxRetries) {
                    waitBeforeRetry(retryCount);
                }

            } catch (SecurityException e) {
                Log.e(TAG, "✗ SecurityException in main loop", e);
                if (handler != null) {
                    android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                    msg.obj = deviceAddress;
                    handler.sendMessage(msg);
                }
                break;

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
                break;
            }
        }

        if (!connected) {
            Log.e(TAG, "Failed to connect to " + deviceName + " after " + retryCount + " attempts");
            if (handler != null) {
                android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                msg.obj = deviceAddress;
                handler.sendMessage(msg);
            }
        }

        close();
    }

    private void cleanupSocket() {
        try {
            if (ois != null) {
                ois.close();
                ois = null;
            }
            if (oos != null) {
                oos.close();
                oos = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error cleaning up socket", ex);
        }
    }

    private void waitBeforeRetry(int attempt) {
        int delay = Math.min(attempt * 2000, 10000);
        Log.d(TAG, "Retrying in " + (delay / 1000) + " seconds...");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void write(Message message) {
        if (oos == null) {
            Log.e(TAG, "Cannot write - stream not initialized");
            return;
        }

        if (!connected) {
            Log.e(TAG, "Cannot write - not connected");
            return;
        }

        try {
            synchronized (oos) {
                oos.writeObject(message);
                oos.flush();
                oos.reset();
            }
            Log.d(TAG, "Sent to " + deviceName + ": " + message.message_id);
        } catch (Exception e) {
            Log.e(TAG, "Write error to " + deviceName, e);
            connected = false;
            // Notify of connection loss
            if (handler != null) {
                android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                msg.obj = deviceAddress;
                handler.sendMessage(msg);
            }
        }
    }

    public void close() {
        running = false;
        connected = false;

        Log.d(TAG, "Closing ClientThread for " + deviceName);

        // Ensure all resources are closed even if exceptions occur
        closeQuietly(oos);
        closeQuietly(ois);
        closeQuietly(socket);

        // Send device address in connection lost message
        if (handler != null) {
            android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
            msg.obj = deviceAddress;
            handler.sendMessage(msg);
            Log.d(TAG, "✓ Sent CONNECTION_LOST message for " + deviceName);
        }

        Thread.currentThread().interrupt();
        Log.d(TAG, "✓✓✓ Cleanup complete for " + deviceName);
    }
    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing resource: " + e.getMessage());
            }
        }
    }
    private void closeQuietly(android.bluetooth.BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing socket: " + e.getMessage());
            }
        }
    }

    public void cancel() {
        running = false;
        close();
    }

    public String getRemoteDeviceAddress() {
        return deviceAddress;
    }

    public BluetoothDevice getRemoteDevice() {
        return device;
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

}