package com.example.dtn.data;


import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface FriendDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Friend friend);

    @Update
    void update(Friend friend);

    @Query("SELECT * FROM friends WHERE deviceId = :deviceId LIMIT 1")
    Friend getFriendById(String deviceId);

    @Query("SELECT * FROM friends")
    List<Friend> getAllFriends();
}
