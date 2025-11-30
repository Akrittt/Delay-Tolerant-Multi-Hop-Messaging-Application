package com.example.dtn.model.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;


import com.example.dtn.model.data.AppDatabase;
import com.example.dtn.model.data.Message;
import com.example.dtn.model.data.MessageDao;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for Message data
 * Single source of truth for all message-related operations
 */
public class MessageRepository {

    private static final String TAG = "MessageRepository";
    private static MessageRepository instance;

    private final MessageDao messageDao;
    private final ExecutorService executorService;

    // LiveData for observing messages
    private final MutableLiveData<List<Message>> allMessages = new MutableLiveData<>();
    private final MutableLiveData<List<Message>> undeliveredMessages = new MutableLiveData<>();
    private final MutableLiveData<Integer> messageCount = new MutableLiveData<>(0);

    private MessageRepository(Context context) {
        AppDatabase database = AppDatabase.getDatabase(context.getApplicationContext());
        messageDao = database.messageDao();
        executorService = Executors.newSingleThreadExecutor();

        // Initial load
        loadAllMessages();
    }

    public static synchronized MessageRepository getInstance(Context context) {
        if (instance == null) {
            instance = new MessageRepository(context);
        }
        return instance;
    }


    public LiveData<List<Message>> getAllMessages() {
        return allMessages;
    }

    public LiveData<List<Message>> getUndeliveredMessages() {
        return undeliveredMessages;
    }

    public LiveData<Integer> getMessageCount() {
        return messageCount;
    }

    /**
     * Insert a new message
     */
    public void insert(Message message, RepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                messageDao.insert(message);
                Log.d(TAG, "✓ Message inserted: " + message.message_id);

                // Reload data
                loadAllMessages();
                loadUndeliveredMessages();

                if (callback != null) {
                    callback.onSuccess(null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error inserting message", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Update existing message
     */
    public void update(Message message, RepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                messageDao.update(message);
                Log.d(TAG, "✓ Message updated: " + message.message_id);

                loadAllMessages();
                loadUndeliveredMessages();

                if (callback != null) {
                    callback.onSuccess(null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating message", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get message by ID (synchronous, for background threads)
     */
    public Message getMessageById(String messageId) {
        try {
            return messageDao.getMessageById(messageId);
        } catch (Exception e) {
            Log.e(TAG, "Error getting message by ID", e);
            return null;
        }
    }

    /**
     * Get messages to forward
     */
    public void getMessagesToForward(long currentTime, RepositoryCallback<List<Message>> callback) {
        executorService.execute(() -> {
            try {
                List<Message> messages = messageDao.getMessagesToForward(currentTime);
                Log.d(TAG, "✓ Retrieved " + messages.size() + " messages to forward");

                if (callback != null) {
                    callback.onSuccess(messages);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting messages to forward", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get messages for specific destination
     */
    public void getMessagesForDestination(String destinationId, RepositoryCallback<List<Message>> callback) {
        executorService.execute(() -> {
            try {
                List<Message> messages = messageDao.getMessagesForDestination(destinationId);
                Log.d(TAG, "✓ Retrieved " + messages.size() + " messages for " + destinationId);

                if (callback != null) {
                    callback.onSuccess(messages);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting messages for destination", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get non-expired messages
     */
    public void getNonExpiredMessages(long currentTime, RepositoryCallback<List<Message>> callback) {
        executorService.execute(() -> {
            try {
                List<Message> messages = messageDao.getNonExpiredMessages(currentTime);
                Log.d(TAG, "✓ Retrieved " + messages.size() + " non-expired messages");

                if (callback != null) {
                    callback.onSuccess(messages);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting non-expired messages", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Delete expired messages
     */
    public void deleteExpiredMessages(long currentTime, RepositoryCallback<Integer> callback) {
        executorService.execute(() -> {
            try {
                int deleted = messageDao.deleteExpiredMessages(currentTime);
                Log.d(TAG, "✓ Deleted " + deleted + " expired messages");

                loadAllMessages();

                if (callback != null) {
                    callback.onSuccess(deleted);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting expired messages", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Delete all messages (for testing)
     */
    public void deleteAll(RepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                messageDao.deleteAll();
                Log.d(TAG, "✓ Deleted all messages");

                loadAllMessages();
                loadUndeliveredMessages();

                if (callback != null) {
                    callback.onSuccess(null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting all messages", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Load all messages and update LiveData
     */
    private void loadAllMessages() {
        executorService.execute(() -> {
            try {
                List<Message> messages = messageDao.getAllMessages();
                allMessages.postValue(messages);
                messageCount.postValue(messages.size());
                Log.d(TAG, "✓ Loaded " + messages.size() + " messages");
            } catch (Exception e) {
                Log.e(TAG, "Error loading all messages", e);
            }
        });
    }

    /**
     * Load undelivered messages and update LiveData
     */
    private void loadUndeliveredMessages() {
        executorService.execute(() -> {
            try {
                List<Message> messages = messageDao.getMessagesToForward(System.currentTimeMillis());
                undeliveredMessages.postValue(messages);
                Log.d(TAG, "✓ Loaded " + messages.size() + " undelivered messages");
            } catch (Exception e) {
                Log.e(TAG, "Error loading undelivered messages", e);
            }
        });
    }

    /**
     * Refresh all LiveData
     */
    public void refresh() {
        loadAllMessages();
        loadUndeliveredMessages();
    }

    /**
     * Get MessageDao for routing protocols
     */
    public MessageDao getMessageDao() {
        return messageDao;
    }

    /**
     * Shutdown executor
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            Log.d(TAG, "✓ Executor shutdown");
        }
    }

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }
}