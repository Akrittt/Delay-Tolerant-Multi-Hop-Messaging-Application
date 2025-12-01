package com.example.dtn.model.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface FriendDao {

    /**
     * Insert a new friend into the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Friend friend);

    /**
     * Update an existing friend's information in the database
     */
    @Update
    void update(Friend friend);

    /**
     * Retrieve a specific friend by their device ID.
     */
    @Query("SELECT * FROM friends WHERE device_id = :deviceId LIMIT 1")
    Friend getFriendById(String deviceId);

    /**
     * Retrieve all friends from the database.
     */
    @Query("SELECT * FROM friends")
    List<Friend> getAllFriends();

    /**
     * Delete a specific friend from the database.
     */
    @Delete
    void delete(Friend friend);

    /**
     * Delete all friends from the database.
     */
    @Query("DELETE FROM friends")
    void deleteAll();

    /**
     * Get the total number of friends in the database.
     */
    @Query("SELECT COUNT(*) FROM friends")
    int getFriendCount();




}
