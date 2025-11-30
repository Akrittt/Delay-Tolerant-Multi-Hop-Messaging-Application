package com.example.dtn.model.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.dtn.data.AppDatabase;
import com.example.dtn.data.Friend;
import com.example.dtn.data.FriendDao;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FriendRepository - Repository for Friend data
 * RESPONSIBILITIES:
 * - Manage all friend-related database operations
 * - Provide LiveData for observing friends
 * - Handle background threading for database operations
 * - Provide callbacks for async operations
 * - Single source of truth for friend data
 */
public class FriendRepository {

    private static final String TAG = "FriendRepository";
    private static FriendRepository instance;

    private final FriendDao friendDao;
    private final ExecutorService executorService;

    // LiveData for observing friends
    private final MutableLiveData<List<Friend>> allFriends = new MutableLiveData<>();
    private final MutableLiveData<Integer> friendCount = new MutableLiveData<>(0);

    /**
     * Private constructor (Singleton pattern)
     */
    private FriendRepository(Context context) {
        AppDatabase database = AppDatabase.getDatabase(context.getApplicationContext());
        friendDao = database.friendDao();
        executorService = Executors.newSingleThreadExecutor();

        // Initial load
        loadAllFriends();

        Log.d(TAG, "✓ FriendRepository initialized");
    }

    /**
     * Get singleton instance
     */
    public static synchronized FriendRepository getInstance(Context context) {
        if (instance == null) {
            instance = new FriendRepository(context);
        }
        return instance;
    }

    // ==================== LiveData Getters ====================

    /**
     * Get LiveData for all friends (for observing in ViewModel)
     */
    public LiveData<List<Friend>> getAllFriends() {
        return allFriends;
    }

    /**
     * Get LiveData for friend count (for UI display)
     */
    public LiveData<Integer> getFriendCount() {
        return friendCount;
    }

    // ==================== Database Operations ====================

    /**
     * Insert a new friend or update if exists
     */
    public void insertOrUpdate(Friend friend, RepositoryCallback<Void> callback) {
        if (friend == null) {
            Log.e(TAG, "Cannot insert null friend");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Friend cannot be null"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                Friend existing = friendDao.getFriendById(friend.deviceId);

                if (existing == null) {
                    // New friend
                    friendDao.insert(friend);
                    Log.d(TAG, "✓ Friend added: " + friend.friendlyName);
                } else {
                    // Update existing
                    friend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(friend);
                    Log.d(TAG, "✓ Friend updated: " + friend.friendlyName);
                }

                // Reload all friends
                loadAllFriends();

                if (callback != null) {
                    callback.onSuccess(null);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error inserting/updating friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Insert a new friend (will replace if exists)
     */
    public void insert(Friend friend, RepositoryCallback<Void> callback) {
        if (friend == null) {
            Log.e(TAG, "Cannot insert null friend");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Friend cannot be null"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                friendDao.insert(friend);
                Log.d(TAG, "✓ Friend inserted: " + friend.friendlyName);

                loadAllFriends();

                if (callback != null) {
                    callback.onSuccess(null);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error inserting friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Update existing friend
     */
    public void update(Friend friend, RepositoryCallback<Void> callback) {
        if (friend == null) {
            Log.e(TAG, "Cannot update null friend");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Friend cannot be null"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                friendDao.update(friend);
                Log.d(TAG, "✓ Friend updated: " + friend.friendlyName);

                loadAllFriends();

                if (callback != null) {
                    callback.onSuccess(null);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error updating friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Update friend's last encounter timestamp
     */
    public void updateLastEncounter(String deviceId, RepositoryCallback<Void> callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "Cannot update - device ID is null/empty");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Device ID cannot be null/empty"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                Friend friend = friendDao.getFriendById(deviceId);

                if (friend != null) {
                    friend.lastEncounteredTimestamp = System.currentTimeMillis();
                    friendDao.update(friend);
                    Log.d(TAG, "✓ Updated encounter time for: " + friend.friendlyName);

                    loadAllFriends();

                    if (callback != null) {
                        callback.onSuccess(null);
                    }
                } else {
                    Log.w(TAG, "Friend not found: " + deviceId);
                    if (callback != null) {
                        callback.onError(new Exception("Friend not found: " + deviceId));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error updating last encounter", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get friend by device ID
     */
    public Friend getFriendById(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "Cannot get friend - device ID is null/empty");
            return null;
        }

        try {
            return friendDao.getFriendById(deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error getting friend by ID", e);
            return null;
        }
    }

    /**
     * Get friend by device ID
     */
    public void getFriendByIdAsync(String deviceId, RepositoryCallback<Friend> callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "Cannot get friend - device ID is null/empty");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Device ID cannot be null/empty"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                Friend friend = friendDao.getFriendById(deviceId);

                if (callback != null) {
                    if (friend != null) {
                        callback.onSuccess(friend);
                    } else {
                        callback.onError(new Exception("Friend not found: " + deviceId));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting friend by ID", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Check if device is a friend
     */
    public void isFriend(String deviceId, RepositoryCallback<Boolean> callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            if (callback != null) {
                callback.onSuccess(false);
            }
            return;
        }

        executorService.execute(() -> {
            try {
                Friend friend = friendDao.getFriendById(deviceId);
                boolean isFriend = (friend != null);

                if (callback != null) {
                    callback.onSuccess(isFriend);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking if friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get all friends
     */
    public List<Friend> getAllFriendsSync() {
        try {
            return friendDao.getAllFriends();
        } catch (Exception e) {
            Log.e(TAG, "Error getting all friends", e);
            return null;
        }
    }

    /**
     * Get all friends (async with callback)
     */
    public void getAllFriendsAsync(RepositoryCallback<List<Friend>> callback) {
        executorService.execute(() -> {
            try {
                List<Friend> friends = friendDao.getAllFriends();

                if (callback != null) {
                    callback.onSuccess(friends);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting all friends", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Delete a friend
     */
    public void delete(Friend friend, RepositoryCallback<Void> callback) {
        if (friend == null) {
            Log.e(TAG, "Cannot delete null friend");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Friend cannot be null"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                friendDao.delete(friend);
                Log.d(TAG, "✓ Friend deleted: " + friend.friendlyName);

                loadAllFriends();

                if (callback != null) {
                    callback.onSuccess(null);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error deleting friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Delete friend by device ID
     */
    public void deleteById(String deviceId, RepositoryCallback<Void> callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "Cannot delete - device ID is null/empty");
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Device ID cannot be null/empty"));
            }
            return;
        }

        executorService.execute(() -> {
            try {
                Friend friend = friendDao.getFriendById(deviceId);

                if (friend != null) {
                    friendDao.delete(friend);
                    Log.d(TAG, "✓ Friend deleted: " + friend.friendlyName);

                    loadAllFriends();

                    if (callback != null) {
                        callback.onSuccess(null);
                    }
                } else {
                    Log.w(TAG, "Friend not found: " + deviceId);
                    if (callback != null) {
                        callback.onError(new Exception("Friend not found: " + deviceId));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error deleting friend", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Delete all friends
     */
    public void deleteAll(RepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                friendDao.deleteAll();
                Log.d(TAG, "✓ All friends deleted");

                loadAllFriends();

                if (callback != null) {
                    callback.onSuccess(null);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error deleting all friends", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    /**
     * Get friend count
     */
    public int getFriendCountSync() {
        try {
            return friendDao.getFriendCount();
        } catch (Exception e) {
            Log.e(TAG, "Error getting friend count", e);
            return 0;
        }
    }

    // ==================== Private Helper Methods ====================

    /**
     * Load all friends and update LiveData
     */
    private void loadAllFriends() {
        executorService.execute(() -> {
            try {
                List<Friend> friends = friendDao.getAllFriends();
                allFriends.postValue(friends);
                friendCount.postValue(friends.size());
                Log.d(TAG, "✓ Loaded " + friends.size() + " friends");
            } catch (Exception e) {
                Log.e(TAG, "Error loading all friends", e);
            }
        });
    }

    /**
     * Refresh all LiveData
     */
    public void refresh() {
        loadAllFriends();
        Log.d(TAG, "✓ Refreshed friend data");
    }

    /**
     * Get FriendDao (for routing protocols if needed)
     */
    public FriendDao getFriendDao() {
        return friendDao;
    }

    /**
     * Shutdown executor service
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            Log.d(TAG, "✓ Executor shutdown");
        }
    }

    // ==================== Callback Interface ====================


    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }
}