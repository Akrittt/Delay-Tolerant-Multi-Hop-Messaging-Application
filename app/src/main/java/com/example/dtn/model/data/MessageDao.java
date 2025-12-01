package com.example.dtn.model.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MessageDao {

    /**
     * Insert a new message into the database.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(Message message);

    /**
     * Update an existing message in the database.
     */
    @Update
    void update(Message message);

    /**
     * Retrieve a specific message by its unique ID.
     */
    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    Message getMessageById(String messageId);

    /**
     * Retrieve all messages from the database.
     */
    @Query("SELECT * FROM messages")
    List<Message> getAllMessages();

    /**
     * Get all non-expired messages (TTL check only).
     */
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime")
    List<Message> getNonExpiredMessages(long currentTime);

    /**
     * Get messages eligible for forwarding.
     * Filters by TTL AND delivery status to prevent forwarding already-delivered messages.
     */
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime AND is_delivered = 0")
    List<Message> getMessagesToForward(long currentTime);

    /**
     * Get all messages destined for a specific device.
     * Useful for checking if we have messages for the currently connected peer.
     */
    @Query("SELECT * FROM messages WHERE destination_id = :destinationId AND is_delivered = 0")
    List<Message> getMessagesForDestination(String destinationId);


    /**
     * Delete expired messages to free up storage.
     */
    @Query("DELETE FROM messages WHERE ttl_timestamp < :currentTime")
    int deleteExpiredMessages(long currentTime);

    /**
     * Delete a specific message from the database.
     */
    @Delete
    void delete(Message message);

    /**
     * Delete all messages from the database.
     */
    @Query("DELETE FROM messages")
    void deleteAll();

}
