package com.example.dtn.network;

import android.os.Handler;
import android.util.Log;

import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * This thread runs on the device that acts as the "group owner" in a Wi-Fi Direct group.
 * It waits for a client to connect and handles message transmission.
 */
public class ServerThread extends Thread {
    private static final String TAG = "ServerThread";
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds timeout

    private ServerSocket serverSocket;
    private Socket socket;
    private Handler handler;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;
    private final Object writeLock = new Object();
    private volatile boolean isConnected = false;
    private volatile boolean isRunning = true;

    public static final int MESSAGE_READ = 1; // A code to identify received messages

    public ServerThread(Handler handler) {
        this.handler = handler;
    }

    /**
     * Check if socket connection is established and active
     */
    public boolean isConnected() {
        return isConnected && socket != null && !socket.isClosed();
    }

    @Override
    public void run() {
        try {
            // Create server socket with port reuse enabled
            serverSocket = new ServerSocket(8888);
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(SOCKET_TIMEOUT); // Set accept timeout
            Log.d(TAG, "Server started on port 8888, waiting for client...");

            // Wait for client connection (blocks with timeout)
            socket = serverSocket.accept();
            Log.d(TAG, "Client connected from: " + socket.getInetAddress().getHostAddress());

            // Initialize streams in correct order with flush
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush(); // CRITICAL: Force header write before creating input stream
            ois = new ObjectInputStream(socket.getInputStream());

            isConnected = true;
            Log.d(TAG, "Connection established and streams ready");

            // Loop to continuously listen for incoming messages
            while (isRunning && socket != null && !socket.isClosed()) {
                try {
                    Message receivedMessage = (Message) ois.readObject();
                    if (receivedMessage != null) {
                        Log.d(TAG, "Received message: " + receivedMessage.message_id);
                        // Send the received message to MainActivity UI thread
                        handler.obtainMessage(MESSAGE_READ, receivedMessage).sendToTarget();
                    }
                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "ClassNotFoundException - Message class not found", e);
                } catch (SocketException e) {
                    if (isRunning) {
                        Log.e(TAG, "Socket closed unexpectedly", e);
                    } else {
                        Log.d(TAG, "Socket closed normally");
                    }
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            Log.e(TAG, "Socket accept timeout - no client connected", e);
        } catch (IOException e) {
            // Only log if not an expected closure
            if (isRunning && !"Socket closed".equals(e.getMessage())) {
                Log.e(TAG, "IOException in run()", e);
            } else {
                Log.d(TAG, "Server thread stopped normally");
            }
        } finally {
            isConnected = false;
            isRunning = false;
            close(); // Ensure cleanup
        }
    }

    /**
     * Write a message to the connected client in a thread-safe manner
     */
    public void write(Message message) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot write - not connected");
            return;
        }

        // Use a single-use thread to avoid blocking the caller
        new Thread(() -> {
            synchronized(writeLock) { // Synchronize to prevent concurrent writes
                try {
                    if (oos != null && isConnected) {
                        oos.writeObject(message);
                        oos.flush();
                        oos.reset(); // Prevent memory leaks from object caching
                        Log.d(TAG, "Sent message: " + message.message_id);
                    } else {
                        Log.w(TAG, "Output stream not available");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "IOException while writing message: " + message.message_id, e);
                    isConnected = false;
                }
            }
        }).start();
    }

    /**
     * Closes all sockets and streams to shut down the thread safely
     */
    public void close() {
        isRunning = false;
        isConnected = false;

        Log.d(TAG, "Closing ServerThread");

        try {
            if (ois != null) {
                ois.close();
                ois = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing input stream", e);
        }

        try {
            if (oos != null) {
                oos.close();
                oos = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing output stream", e);
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }

        Log.d(TAG, "ServerThread closed successfully");
    }
}
