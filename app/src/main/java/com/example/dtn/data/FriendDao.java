package com.example.dtn.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for Friend entity operations.
 * Provides methods to interact with the friends table in the Room database.
 */
@Dao
public interface FriendDao {

    /**
     * Insert a new friend into the database.
     * If a friend with the same deviceId already exists, replace it.
     * @param friend The Friend object to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Friend friend);

    /**
     * Update an existing friend's information in the database.
     * Typically used to update lastEncounteredTimestamp after a connection.
     * @param friend The Friend object with updated information
     */
    @Update
    void update(Friend friend);

    /**
     * Retrieve a specific friend by their device ID.
     * @param deviceId The unique device identifier (MAC address or device name)
     * @return The Friend object if found, null otherwise
     */
    @Query("SELECT * FROM friends WHERE device_id = :deviceId LIMIT 1")
    Friend getFriendById(String deviceId);

    /**
     * Retrieve all friends from the database.
     * Useful for displaying the complete friends list in the UI.
     * @return List of all Friend objects in the database
     */
    @Query("SELECT * FROM friends")
    List<Friend> getAllFriends();

    /**
     * Delete a specific friend from the database.
     * @param friend The Friend object to delete
     */
    @Delete
    void delete(Friend friend);

    /**
     * Delete all friends from the database.
     * Useful for testing or resetting the app.
     */
    @Query("DELETE FROM friends")
    void deleteAll();

    /**
     * Get the total number of friends in the database.
     * @return Count of friends
     */
    @Query("SELECT COUNT(*) FROM friends")
    int getFriendCount();


}
