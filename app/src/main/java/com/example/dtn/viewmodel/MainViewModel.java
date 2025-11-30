package com.example.dtn.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.dtn.model.data.Friend;
import com.example.dtn.model.data.Message;
import com.example.dtn.model.repository.FriendRepository;
import com.example.dtn.model.repository.MessageRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * MainViewModel - Central ViewModel for MainActivity
 * Manages overall app state and coordinates between repositories
 */
public class MainViewModel extends AndroidViewModel {

    private static final String TAG = "MainViewModel";

    // Repositories
    private final MessageRepository messageRepository;
    private final FriendRepository friendRepository;

    // Connection state
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<String> connectionStatus = new MutableLiveData<>("Disconnected");
    private final MutableLiveData<Integer> connectionColor = new MutableLiveData<>(0xFFF44336); // Red

    // Protocol state
    private final MutableLiveData<String> currentProtocol = new MutableLiveData<>("EPIDEMIC");
    private final MutableLiveData<String> protocolDisplay = new MutableLiveData<>("Protocol: Epidemic Routing 🟠");

    // Transport state
    public enum TransportType { WIFI_DIRECT, BLUETOOTH }
    private final MutableLiveData<TransportType> activeTransport = new MutableLiveData<>(TransportType.WIFI_DIRECT);
    private final MutableLiveData<String> transportDisplay = new MutableLiveData<>("Transport: Wi-Fi Direct 📡 (Fast)");

    // Device info
    private final MutableLiveData<String> ownDeviceId = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> isDeviceIdReady = new MutableLiveData<>(false);

    // Chat messages (for UI display)
    private final MutableLiveData<List<ChatMessage>> chatMessages = new MutableLiveData<>(new ArrayList<>());

    // Peer list
    private final MutableLiveData<List<String>> discoveredPeers = new MutableLiveData<>(new ArrayList<>());

    public MainViewModel(@NonNull Application application) {
        super(application);

        // Initialize repositories
        messageRepository = MessageRepository.getInstance(application);
        friendRepository = FriendRepository.getInstance(application);

        Log.d(TAG, "✓ MainViewModel initialized");
    }

    // ==================== LiveData Getters ====================

    // Connection
    public LiveData<Boolean> getIsConnected() { return isConnected; }
    public LiveData<String> getConnectionStatus() { return connectionStatus; }
    public LiveData<Integer> getConnectionColor() { return connectionColor; }

    // Protocol
    public LiveData<String> getCurrentProtocol() { return currentProtocol; }
    public LiveData<String> getProtocolDisplay() { return protocolDisplay; }

    // Transport
    public LiveData<TransportType> getActiveTransport() { return activeTransport; }
    public LiveData<String> getTransportDisplay() { return transportDisplay; }

    // Device
    public LiveData<String> getOwnDeviceId() { return ownDeviceId; }
    public LiveData<Boolean> getIsDeviceIdReady() { return isDeviceIdReady; }

    // Messages & Peers
    public LiveData<List<ChatMessage>> getChatMessages() { return chatMessages; }
    public LiveData<List<String>> getDiscoveredPeers() { return discoveredPeers; }

    // Repository LiveData
    public LiveData<List<Message>> getAllMessages() { return messageRepository.getAllMessages(); }
    public LiveData<List<Friend>> getAllFriends() { return friendRepository.getAllFriends(); }
    public LiveData<Integer> getMessageCount() { return messageRepository.getMessageCount(); }
    public LiveData<Integer> getFriendCount() { return friendRepository.getFriendCount(); }

    // ==================== State Setters ====================

    /**
     * Update connection status
     */
    public void setConnectionStatus(boolean connected, String status) {
        isConnected.postValue(connected);
        connectionStatus.postValue(status);

        if (connected) {
            connectionColor.postValue(0xFF4CAF50); // Green
        } else {
            connectionColor.postValue(0xFFF44336); // Red
        }

        Log.d(TAG, "Connection status: " + status);
    }

    /**
     * Set mesh connection status
     */
    public void setMeshConnectionStatus(int activeConnections) {
        if (activeConnections == 0) {
            setConnectionStatus(false, "Status: Disconnected");
        } else if (activeConnections == 1) {
            setConnectionStatus(true, "Status: Connected (1 peer)");
            connectionColor.postValue(0xFFFFC107); // Yellow
        } else {
            setConnectionStatus(true, "Status: Mesh Active (" + activeConnections + " peers)");
            connectionColor.postValue(0xFF4CAF50); // Green
        }
    }

    /**
     * Switch routing protocol
     */
    public void setProtocol(String protocol) {
        currentProtocol.postValue(protocol);

        if ("SPRAY_AND_WAIT".equals(protocol)) {
            protocolDisplay.postValue("Protocol: Spray and Wait 🔵");
        } else {
            protocolDisplay.postValue("Protocol: Epidemic Routing 🟠");
        }

        Log.d(TAG, "Protocol changed to: " + protocol);
    }

    /**
     * Switch transport
     */
    public void setTransport(TransportType transport) {
        activeTransport.postValue(transport);

        if (transport == TransportType.WIFI_DIRECT) {
            transportDisplay.postValue("Transport: Wi-Fi Direct 📡 (Fast)");
        } else {
            transportDisplay.postValue("Transport: Bluetooth 📱 (Compatible)");
        }

        Log.d(TAG, "Transport changed to: " + transport);
    }

    /**
     * Set own device ID
     */
    public void setOwnDeviceId(String deviceId) {
        ownDeviceId.postValue(deviceId);

        if (deviceId != null && !deviceId.isEmpty() && !deviceId.equals("02:00:00:00:00:00")) {
            isDeviceIdReady.postValue(true);
            Log.d(TAG, "✓ Device ID set: " + deviceId);
        } else {
            isDeviceIdReady.postValue(false);
            Log.w(TAG, "⚠️ Invalid device ID: " + deviceId);
        }
    }

    /**
     * Add chat message to UI
     */
    public void addChatMessage(String text, String messageId, boolean isDelivered) {
        List<ChatMessage> current = chatMessages.getValue();
        if (current == null) {
            current = new ArrayList<>();
        }

        current.add(new ChatMessage(text, messageId, isDelivered));
        chatMessages.postValue(current);

        Log.d(TAG, "Chat message added: " + text);
    }

    /**
     * Update chat message delivery status
     */
    public void updateChatMessageDelivery(String messageId, boolean isDelivered) {
        List<ChatMessage> current = chatMessages.getValue();
        if (current == null) return;

        for (ChatMessage msg : current) {
            if (msg.messageId != null && msg.messageId.equals(messageId)) {
                msg.isDelivered = isDelivered;
                break;
            }
        }

        chatMessages.postValue(current);
        Log.d(TAG, "Message delivery updated: " + messageId);
    }

    /**
     * Clear all chat messages
     */
    public void clearChatMessages() {
        chatMessages.postValue(new ArrayList<>());
        Log.d(TAG, "Chat messages cleared");
    }

    /**
     * Update peer list
     */
    public void setDiscoveredPeers(List<String> peers) {
        discoveredPeers.postValue(peers);
        Log.d(TAG, "Peer list updated: " + peers.size() + " peers");
    }

    // ==================== Repository Operations ====================

    /**
     * Insert message
     */
    public void insertMessage(Message message, MessageRepository.RepositoryCallback<Void> callback) {
        messageRepository.insert(message, callback);
    }

    /**
     * Update message
     */
    public void updateMessage(Message message, MessageRepository.RepositoryCallback<Void> callback) {
        messageRepository.update(message, callback);
    }

    /**
     * Get messages to forward
     */
    public void getMessagesToForward(MessageRepository.RepositoryCallback<List<Message>> callback) {
        messageRepository.getMessagesToForward(System.currentTimeMillis(), callback);
    }

    /**
     * Insert or update friend
     */
    public void insertOrUpdateFriend(Friend friend, FriendRepository.RepositoryCallback<Void> callback) {
        friendRepository.insertOrUpdate(friend, callback);
    }

    /**
     * Update friend encounter time
     */
    public void updateFriendEncounter(String deviceId, FriendRepository.RepositoryCallback<Void> callback) {
        friendRepository.updateLastEncounter(deviceId, callback);
    }

    /**
     * Check if device is a friend
     */
    public void isFriend(String deviceId, FriendRepository.RepositoryCallback<Boolean> callback) {
        friendRepository.isFriend(deviceId, callback);
    }

    // ==================== Cleanup ====================

    @Override
    protected void onCleared() {
        super.onCleared();
        messageRepository.shutdown();
        friendRepository.shutdown();
        Log.d(TAG, "✓ MainViewModel cleared");
    }

    // ==================== Inner Classes ====================

    /**
     * Chat message for UI display
     */
    public static class ChatMessage {
        public String text;
        public String messageId;
        public boolean isDelivered;

        public ChatMessage(String text, String messageId, boolean isDelivered) {
            this.text = text;
            this.messageId = messageId;
            this.isDelivered = isDelivered;
        }

        @NonNull
        @Override
        public String toString() {
            return text + (isDelivered ? " ✓" : " ...");
        }
    }
}