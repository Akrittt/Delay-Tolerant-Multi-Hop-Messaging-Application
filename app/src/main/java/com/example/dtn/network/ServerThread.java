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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * FIXED: ServerThread with message queue for ordered delivery
 */
public class ServerThread extends Thread {
    private static final String TAG = "ServerThread";
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds (reduced)

    private ServerSocket serverSocket;
    private Socket socket;
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

    public ServerThread(Handler handler) {
        this.handler = handler;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public boolean isConnected() {
        return isConnected && socket != null && !socket.isClosed();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(8888);
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(0);  // No timeout
            Log.d(TAG, "Server socket timeout set to: no timeout");
            Log.d(TAG, "Server listening on port 8888");

            socket = serverSocket.accept();
            Log.d(TAG, "Client connected from: " + socket.getInetAddress().getHostAddress());

            // FIXED: Initialize streams properly
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(socket.getInputStream());

            isConnected = true;
            lastActivityTime = System.currentTimeMillis();

            // FIXED: Spawn separate reader/writer threads
            readThread = new Thread(this::readLoop);
            writeThread = new Thread(this::writeLoop);

            readThread.start();
            writeThread.start();

            readThread.join();
            writeThread.join();

        } catch (IOException | InterruptedException e) {
            if (isRunning) {
                Log.e(TAG, "Error", e);
            }
        } finally {
            isConnected = false;
            isRunning = false;
            close();
        }
    }

    private void readLoop() {
        while (isRunning && !socket.isClosed()) {
            try {
                Message receivedMessage = (Message) ois.readObject();
                if (receivedMessage != null) {
                    lastActivityTime = System.currentTimeMillis();
                    Log.d(TAG, "Received: " + receivedMessage.message_id);
                    handler.obtainMessage(MESSAGE_READ, receivedMessage).sendToTarget();
                }
            } catch (SocketException e) {
                if (isRunning) Log.e(TAG, "Socket error", e);
                break;
            } catch (ClassNotFoundException | IOException e) {
                if (isRunning) Log.e(TAG, "Read error", e);
                break;
            }
        }
    }

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
                    Log.d(TAG, "Sent: " + message.message_id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                Log.e(TAG, "Write error", e);
                isConnected = false;
                break;
            }
        }
    }

    public void write(Message message) {
        if (!isConnected()) {
            Log.w(TAG, "Not connected");
            return;
        }

        try {
            writeQueue.offer(message, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Queue interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        isRunning = false;
        isConnected = false;

        try {
            if (ois != null) ois.close();
            if (oos != null) oos.close();
            if (socket != null && !socket.isClosed()) socket.close();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Close error", e);
        }

        Log.d(TAG, "ServerThread closed");
    }
}
