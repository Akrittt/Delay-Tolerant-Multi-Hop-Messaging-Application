package com.example.dtn.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MessageDao {

    // ... insert, update, getMessageById methods are unchanged ...
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insert(Message message);
    @Update void update(Message message);
    @Query("SELECT * FROM messages WHERE message_id = :messageId") Message getMessageById(String messageId);

    @Query("SELECT * FROM messages")
    List<Message> getAllMessages();

    // --- NEW METHOD ---
    // This query gets all messages where the TTL timestamp is in the future.
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime")
    List<Message> getNonExpiredMessages(long currentTime);
}
