package com.example.dtn.network;

import android.os.Handler;
import android.util.Log;

import com.example.dtn.data.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * FIXED: ClientThread with message queue for ordered delivery
 * Separate threads for reading and writing to prevent blocking
 */
public class ClientThread extends Thread {
    private static final String TAG = "ClientThread";
    private static final int CONNECTION_TIMEOUT = 10000; // 10 seconds
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds (reduced from 10min)
    private static final int KEEPALIVE_INTERVAL = 10000; // 10 seconds

    private Socket socket;
    private String hostAddress;
    private Handler handler;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;
    private final BlockingQueue<Message> writeQueue = new LinkedBlockingQueue<>();
    private volatile boolean isConnected = false;
    private volatile boolean isRunning = true;

    public static final int MESSAGE_READ = 1;

    private Thread writeThread;
    private Thread readThread;
    private long lastActivityTime;

    public ClientThread(InetAddress hostAddress, Handler handler) {
        this.hostAddress = hostAddress.getHostAddress();
        this.handler = handler;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public boolean isConnected() {
        return isConnected && socket != null && !socket.isClosed();
    }

    @Override
    public void run() {
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(hostAddress, 8888), CONNECTION_TIMEOUT);
            socket.setSoTimeout(0);  // No timeout for reads
            Log.d(TAG, "Client socket timeout set to: no timeout");
            Log.d(TAG, "Connected to server at: " + hostAddress);

            // FIXED: Initialize streams in correct order
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(socket.getInputStream());

            isConnected = true;
            lastActivityTime = System.currentTimeMillis();

            // FIXED: Spawn separate threads for reading and writing
            readThread = new Thread(this::readLoop);
            writeThread = new Thread(this::writeLoop);

            readThread.start();
            writeThread.start();

            Log.d(TAG, "Reader and Writer threads started");

            // Wait for threads to complete
            readThread.join();
            writeThread.join();

        } catch (SocketTimeoutException e) {
            Log.e(TAG, "Connection timeout", e);
        } catch (IOException e) {
            if (isRunning) {
                Log.e(TAG, "IOException", e);
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "ClientThread interrupted");
            Thread.currentThread().interrupt();
        } finally {
            isConnected = false;
            isRunning = false;
            close();
        }
    }

    /**
     * Separate read loop
     */
    private void readLoop() {
        while (isRunning && !socket.isClosed()) {
            try {
                // WAIT for message on the network socket
                Message receivedMessage = (Message) ois.readObject();
                if (receivedMessage != null) {
                    lastActivityTime = System.currentTimeMillis();
                    Log.d(TAG, "Received message: " + receivedMessage.message_id);
                    // SEND TO MAIN THREAD VIA HANDLER
                    handler.obtainMessage(MESSAGE_READ, receivedMessage).sendToTarget();
                }
            } catch (SocketTimeoutException e) {
                Log.d(TAG, "Timeout - waiting for data");
                checkKeepalive();
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "ClassNotFoundException", e);
            } catch (SocketException e) {
                if (isRunning) {
                    Log.e(TAG, "Socket closed unexpectedly", e);
                }
                break;
            } catch (IOException e) {
                Log.e(TAG, "IOException", e);
                break;
            }
        }
        Log.d(TAG, "Read loop ended");
    }

    /**
     * FIXED: Separate write loop with message queue
     */
    private void writeLoop() {
        while (isRunning && !socket.isClosed()) {
            try {
                Message message = writeQueue.poll(5, TimeUnit.SECONDS);
                if (message != null) {
                    synchronized (oos) {
                        oos.writeObject(message);
                        oos.flush();
                        oos.reset();
                    }
                    lastActivityTime = System.currentTimeMillis();
                    Log.d(TAG, "Sent message: " + message.message_id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "IOException in writeLoop", e);
                }
                isConnected = false;
                break;
            }
        }
        Log.d(TAG, "Write loop ended");
    }

    /**
     * FIXED: Keepalive check
     */
    private void checkKeepalive() {
        long timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime;
        if (timeSinceLastActivity > KEEPALIVE_INTERVAL * 2) {
            Log.w(TAG, "No activity for " + timeSinceLastActivity + "ms - reconnecting");
            isConnected = false;
        }
    }

    /**
     * Queue message instead of spawning thread
     */
    public void write(Message message) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot write - not connected");
            return;
        }

        try {
            boolean added = writeQueue.offer(message, 5, TimeUnit.SECONDS);
            if (!added) {
                Log.w(TAG, "Write queue full, message dropped: " + message.message_id);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while queuing message", e);
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        isRunning = false;
        isConnected = false;

        Log.d(TAG, "Closing ClientThread");

        try {
            if (ois != null) ois.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing input stream", e);
        }

        try {
            if (oos != null) oos.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing output stream", e);
        }

        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }

        Log.d(TAG, "ClientThread closed");
    }
}
