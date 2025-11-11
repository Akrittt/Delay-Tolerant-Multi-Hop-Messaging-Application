package com.example.dtn.network;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.example.dtn.MainActivity;
import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
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
    private BluetoothSocket socket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private volatile boolean running = true;
    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_CONNECTION_ESTABLISHED = 2;
    public static final int MESSAGE_CONNECTION_LOST = 3;




    public BluetoothServerThread(BluetoothAdapter adapter, Handler handler) {
        this.adapter = adapter;
        this.handler = handler;
        setName("BluetoothServerThread");
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {
        try {
            Log.d(TAG, "Starting Bluetooth server...");

            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, BT_UUID);
                Log.d(TAG, "✓ Bluetooth server listening on " + SERVICE_NAME);

            } catch (SecurityException e) {
                Log.e(TAG, "❌❌❌ SecurityException: Missing Bluetooth permission!", e);
                Log.e(TAG, "Please ensure BLUETOOTH_CONNECT permission is granted");

                // Notify MainActivity
                android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
                msg.obj = "PERMISSION_DENIED";
                handler.sendMessage(msg);

                return; // ✓ Exit thread
            }

            // ✓ Main server loop - accept multiple clients sequentially
            while (running) {
                try {
                    Log.d(TAG, "Waiting for client connection...");

                    // ✓ Accept incoming connection (blocks until client connects)
                    socket = serverSocket.accept();

                    if (socket != null) {
                        String clientName = "Unknown";
                        String clientAddress = "Unknown";

                        try {
                            clientName = socket.getRemoteDevice().getName();
                            clientAddress = socket.getRemoteDevice().getAddress();
                        } catch (SecurityException e) {
                            Log.w(TAG, "Cannot get device info - permission denied");
                        }

                        Log.d(TAG, "✓ Client connected: " + clientName + " (" + clientAddress + ")");

                        // ✓ Handle this client connection (blocks until disconnected)
                        handleClientConnection(socket);

                        // After client disconnects, loop back to accept next client
                        Log.d(TAG, "Ready to accept new connections");
                    }

                } catch (IOException acceptException) {
                    if (running) {
                        Log.e(TAG, "Server accept() failed", acceptException);
                        // Continue trying to accept connections
                    } else {
                        Log.d(TAG, "Server socket closed intentionally");
                        break;
                    }
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Server socket creation failed", e);
        } finally {
            close();
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void handleClientConnection(BluetoothSocket clientSocket) {
        try {
            String deviceName = "Unknown";
            String deviceAddress = "Unknown";

            try {
                deviceName = clientSocket.getRemoteDevice().getName();
                deviceAddress = clientSocket.getRemoteDevice().getAddress();
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot get device name");
            }

            Log.d(TAG, "Initializing streams for " + deviceName + "...");

            // ✓ SERVER INITIALIZES FIRST
            oos = new ObjectOutputStream(clientSocket.getOutputStream());
            oos.flush(); // CRITICAL: Send header immediately
            Log.d(TAG, "✓ Server: OutputStream created and flushed");

            // ✓ Give client time to receive header and initialize its OutputStream
            Thread.sleep(300);

            // ✓ Now safe to create InputStream (client's header should be ready)
            ois = new ObjectInputStream(clientSocket.getInputStream());
            Log.d(TAG, "✓ Server: InputStream created");

            // ✓ HANDSHAKE PROTOCOL
            Log.d(TAG, "Starting handshake with " + deviceName);

            // Send server ready signal
            oos.writeObject("SERVER_READY");
            oos.flush();
            Log.d(TAG, "✓ Server: Sent SERVER_READY");

            // Wait for client acknowledgment (blocking read is OK here)
            Object clientResponse = ois.readObject();

            if (!"CLIENT_READY".equals(clientResponse)) {
                throw new IOException("Invalid handshake response: " + clientResponse);
            }

            Log.d(TAG, "✓ Server: Received CLIENT_READY");
            Log.d(TAG, "✓✓✓ Handshake complete with " + deviceName + " ✓✓✓");

            // ✓ Update connection status
            MainActivity.isBluetoothConnected = true;

            // ✓ Now connection is fully established - notify MainActivity
            android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_ESTABLISHED);
            msg.obj = deviceName;
            handler.sendMessage(msg);

            // ✓ Enter message loop
            Log.d(TAG, "Entering message loop with " + deviceName);

            while (running && clientSocket.isConnected()) {
                try {
                    Message message = (Message) ois.readObject();

                    if (message != null) {
                        Log.d(TAG, "📨 Received from " + deviceName + ": " + message.message_id);
                        handler.obtainMessage(MESSAGE_READ, message).sendToTarget();
                    }

                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "ClassNotFoundException", e);
                    break;

                } catch (IOException e) {
                    Log.e(TAG, "Connection lost while reading from " + deviceName, e);
                    break;
                }
            }

            Log.d(TAG, "Exited message loop with " + deviceName);

        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted", e);
            Thread.currentThread().interrupt();

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Handshake error", e);

        } catch (IOException e) {
            Log.e(TAG, "Error during connection", e);

        } finally {
            closeClientConnection(clientSocket);
            android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
            handler.sendMessage(msg);
        }
    }



    private void closeClientConnection(BluetoothSocket clientSocket) {
        Log.d(TAG, "Closing client connection...");

        MainActivity.isBluetoothConnected = false;

        // ✓ Close in correct order: Streams → Socket
        if (ois != null) {
            try {
                ois.close();
                Log.d(TAG, "✓ Input stream closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream", e);
            } finally {
                ois = null;
            }
        }

        if (oos != null) {
            try {
                oos.close();
                Log.d(TAG, "✓ Output stream closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream", e);
            } finally {
                oos = null;
            }
        }

        if (clientSocket != null) {
            try {
                clientSocket.close();
                Log.d(TAG, "✓ Client socket closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }

        // ✓ Server socket stays open for new connections
    }


    public void write(Message message) {
        // ✓ Check if stream is available
        if (oos == null) {
            Log.e(TAG, "Cannot write - no client connected");
            return;
        }

        try {
            synchronized (this) {  // ✓ Synchronize on 'this' instead of oos
                oos.writeObject(message);
                oos.flush();
                oos.reset();  // ✓ Prevent memory leak from ObjectOutputStream cache
            }
            Log.d(TAG, "✓ Sent: " + message.message_id);

        } catch (IOException e) {
            Log.e(TAG, "Write error - connection may be lost", e);

            // ✓ Notify connection lost
            android.os.Message msg = handler.obtainMessage(MESSAGE_CONNECTION_LOST);
            handler.sendMessage(msg);
        }
    }

    public void close() {
        Log.d(TAG, "Closing BluetoothServerThread...");

        running = false;
        MainActivity.isBluetoothConnected = false;

        // ✓ Close streams
        if (ois != null) {
            try {
                ois.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream", e);
            } finally {
                ois = null;
            }
        }

        if (oos != null) {
            try {
                oos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream", e);
            } finally {
                oos = null;
            }
        }

        // ✓ Close client socket
        if (socket != null) {
            try {
                socket.close();
                Log.d(TAG, "✓ Client socket closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            } finally {
                socket = null;
            }
        }

        // ✓ Close server socket LAST
        if (serverSocket != null) {
            try {
                serverSocket.close();
                Log.d(TAG, "✓ Server socket closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            } finally {
                serverSocket = null;
            }
        }

        Log.d(TAG, "✓✓✓ BluetoothServerThread cleanup complete");
    }




}
