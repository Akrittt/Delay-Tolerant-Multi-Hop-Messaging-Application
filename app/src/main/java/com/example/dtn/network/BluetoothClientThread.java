package com.example.dtn.network;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.MainActivity;
import com.example.dtn.data.Message;

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

    private final BluetoothDevice device;
    private final Handler handler;
    private BluetoothSocket socket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private volatile boolean running = true;
    private volatile boolean connected = false;
    public BluetoothClientThread(BluetoothDevice device, Handler handler) {
        this.device = device;
        this.handler = handler;
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        String deviceName;
        try {
            deviceName = device.getName();
            if (deviceName == null) deviceName = "Unknown";
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot get device name - permission denied");
        }

        Log.d(TAG, "ClientThread: Connecting to " + device.getName());

        int maxRetries = 5;
        int retryCount = 0;

        while (retryCount < maxRetries && !connected && running) {
            try {
                retryCount++;
                Log.d(TAG, "Connection attempt " + retryCount + "/" + maxRetries);

                // Create socket
                try {
                    socket = device.createRfcommSocketToServiceRecord(BT_UUID);
                    Log.d(TAG, "✓ Socket created");

                } catch (SecurityException e) {
                    Log.e(TAG, "❌❌❌ SecurityException: BLUETOOTH_CONNECT permission denied!", e);
                    if (handler != null) {
                        android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                        msg.obj = "PERMISSION_DENIED";
                        handler.sendMessage(msg);
                    }
                    return;
                }

                // Try to connect
                try {
                    socket.connect();
                    Log.d(TAG, "✓ Socket connected to " + device.getName());
                    connected = true;

                } catch (SecurityException e) {
                    Log.e(TAG, "❌ SecurityException during connect", e);
                    throw new IOException("Permission denied during connect");
                }

                // ✓✓✓ CRITICAL: CLIENT WAITS FOR SERVER TO INITIALIZE FIRST
                Log.d(TAG, "Waiting for server to initialize streams...");
                Thread.sleep(500); // Give server time to create and flush its OutputStream

                Log.d(TAG, "Initializing client streams...");

                // ✓ CLIENT CREATES STREAMS SECOND
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush(); // Send header to server
                Log.d(TAG, "✓ Client: OutputStream created and flushed");

                Thread.sleep(200); // Brief delay

                ois = new ObjectInputStream(socket.getInputStream());
                Log.d(TAG, "✓ Client: InputStream created");

                // ✓✓✓ HANDSHAKE PROTOCOL - CRITICAL FOR SYNCHRONIZATION
                Log.d(TAG, "Starting handshake with server...");

                // Wait for server ready signal
                Object serverResponse = ois.readObject();

                if (!"SERVER_READY".equals(serverResponse)) {
                    throw new IOException("Invalid handshake from server: " + serverResponse);
                }

                Log.d(TAG, "✓ Client: Received SERVER_READY");

                // Send acknowledgment
                oos.writeObject("CLIENT_READY");
                oos.flush();
                Log.d(TAG, "✓ Client: Sent CLIENT_READY");

                Log.d(TAG, "✓✓✓ Handshake complete - Connection established ✓✓✓");

                // ✓ Notify MainActivity of successful connection
                if (handler != null) {
                    // Define MESSAGE_CONNECTED constant if not already defined
                    final int MESSAGE_CONNECTED = 2;
                    android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTED);
                    msg.obj = device.getName();
                    handler.sendMessage(msg);
                }

                // Update connection flag on main thread
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    Log.d(TAG, "✓ UI Updated: Connected to " + device.getName());
                    MainActivity.isBluetoothConnected = true;
                });

                // ✓ Now enter message reading loop
                Log.d(TAG, "Entering message loop");

                while (running && socket.isConnected()) {
                    try {
                        Message message = (Message) ois.readObject();
                        if (message != null) {
                            Log.d(TAG, "📨 Received: " + message.message_id);
                            handler.obtainMessage(MESSAGE_READ, message).sendToTarget();
                        }
                    } catch (ClassNotFoundException e) {
                        Log.e(TAG, "ClassNotFoundException", e);
                        break;
                    } catch (IOException e) {
                        Log.e(TAG, "Connection lost while reading: " + e.getMessage());
                        break;
                    }
                }

                // If we exit loop, connection is lost
                Log.w(TAG, "Exited message loop - connection lost");
                handler.obtainMessage(MESSAGE_CONNECTION_LOST).sendToTarget();
                connected = false;

                // Connection succeeded, so break retry loop
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
                Log.w(TAG, "Connection failed (attempt " + retryCount + "): " + e.getMessage());
                cleanupSocket();

                if (retryCount < maxRetries) {
                    waitBeforeRetry(retryCount);
                }

            } catch (SecurityException e) {
                Log.e(TAG, "❌ SecurityException in main loop", e);
                if (handler != null) {
                    android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                    msg.obj = "PERMISSION_DENIED";
                    handler.sendMessage(msg);
                }
                break;

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
                break;
            }
        }

        if (!connected) {
            Log.e(TAG, "Failed to connect after " + retryCount + " attempts");
            if (handler != null) {
                handler.obtainMessage(MESSAGE_CONNECTION_LOST).sendToTarget();
            }
        }

        close();
    }
    // Helper method to clean up socket after failed attempt
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

    // Helper method for exponential backoff
    private void waitBeforeRetry(int attempt) {
        int delay = Math.min(attempt * 2000, 10000); // Max 10 seconds
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
            Log.d(TAG, "Sent: " + message.message_id);
        } catch (Exception e) {
            Log.e(TAG, "Write error", e);
        }
    }

    public void close() {
        running = false;
        connected = false;
        MainActivity.isBluetoothConnected = false;
        try {
            //close output stream
            if (oos != null) {
                try {
                    oos.close();
                    Log.d(TAG, "✓ Output stream closed");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing output stream", e);
                } finally {
                    oos = null;  // ✓ Prevent memory leak
                }
            }
            // close input stream
            if (ois != null) {
                try {
                    ois.close();
                    Log.d(TAG, "✓ Input stream closed");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream", e);
                } finally {
                    ois = null;  // ✓ Prevent memory leak
                }
            }
            // close socket
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "✓ Socket closed");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing socket", e);
                } finally {
                    socket = null;  // ✓ Prevent memory leak
                }
            }
        } finally {
            // ✓ Notify MainActivity that connection was lost
            if (handler != null) {
                android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                handler.sendMessage(msg);
                Log.d(TAG, "✓ Sent CONNECTION_LOST message to MainActivity");
            }

            // ✓ Interrupt thread (in case it's blocked on I/O)
            Thread.currentThread().interrupt();
        }

        Log.d(TAG, "✓✓✓ Cleanup complete");
    }
    public void cancel() {
        running = false;
        close();
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }
}
