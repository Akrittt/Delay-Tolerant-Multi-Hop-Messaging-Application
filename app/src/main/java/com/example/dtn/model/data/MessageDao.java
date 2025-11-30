package com.example.dtn.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for Message entity operations.
 * Provides database queries for DTN message management, forwarding, and delivery tracking.
 */
@Dao
public interface MessageDao {

    /**
     * Insert a new message into the database.
     * If a message with the same message_id already exists, ignore the insert.
     * This prevents duplicate messages from being stored during multi-hop forwarding.
     *
     * @param message The Message object to insert
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(Message message);

    /**
     * Update an existing message in the database.
     * Typically used to update delivery status or copy count.
     *
     * @param message The Message object with updated information
     */
    @Update
    void update(Message message);

    /**
     * Retrieve a specific message by its unique ID.
     *
     * @param messageId The unique message identifier (UUID)
     * @return The Message object if found, null otherwise
     */
    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    Message getMessageById(String messageId);

    /**
     * Retrieve all messages from the database.
     * Useful for debugging and displaying complete message history.
     *
     * @return List of all Message objects in the database
     */
    @Query("SELECT * FROM messages")
    List<Message> getAllMessages();

    /**
     * Get all non-expired messages (TTL check only).
     * Used as a base filter before applying routing-specific logic.
     *
     * @param currentTime Current timestamp in milliseconds
     * @return List of messages where ttl_timestamp > currentTime
     */
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime")
    List<Message> getNonExpiredMessages(long currentTime);

    /**
     * CRITICAL: Get messages eligible for forwarding.
     * Filters by TTL AND delivery status to prevent forwarding already-delivered messages.
     * This is the query your routing logic should use in MainActivity.triggerForwardingLogic().
     *
     * @param currentTime Current timestamp in milliseconds
     * @return List of undelivered, non-expired messages
     */
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime AND is_delivered = 0")
    List<Message> getMessagesToForward(long currentTime);

    /**
     * Get all messages destined for a specific device.
     * Useful for checking if we have messages for the currently connected peer.
     *
     * @param destinationId The device ID of the destination
     * @return List of messages for that destination
     */
    @Query("SELECT * FROM messages WHERE destination_id = :destinationId AND is_delivered = 0")
    List<Message> getMessagesForDestination(String destinationId);

    /**
     * Get all messages that originated from this device.
     * Useful for tracking your own sent messages and their delivery status.
     *
     * @param sourceId The device ID of the source (own device)
     * @return List of messages originated from this device
     */
    @Query("SELECT * FROM messages WHERE source_id = :sourceId")
    List<Message> getMessagesBySource(String sourceId);

    /**
     * Get all delivered messages.
     * Useful for statistics and research analysis.
     *
     * @return List of messages where is_delivered = true
     */
    @Query("SELECT * FROM messages WHERE is_delivered = 1")
    List<Message> getDeliveredMessages();

    /**
     * Get all ACK messages.
     * Useful for tracking acknowledgment flow in your DTN network.
     *
     * @return List of ACK-type messages
     */
    @Query("SELECT * FROM messages WHERE message_type = 1")
    List<Message> getAckMessages();

    /**
     * Delete expired messages to free up storage.
     * Should be called periodically during testing.
     *
     * @param currentTime Current timestamp in milliseconds
     * @return Number of messages deleted
     */
    @Query("DELETE FROM messages WHERE ttl_timestamp < :currentTime")
    int deleteExpiredMessages(long currentTime);

    /**
     * Delete a specific message from the database.
     *
     * @param message The Message object to delete
     */
    @Delete
    void delete(Message message);

    /**
     * Delete all messages from the database.
     * Useful for testing or resetting the app between test runs.
     */
    @Query("DELETE FROM messages")
    void deleteAll();

    /**
     * Get the total number of messages in the database.
     * Useful for monitoring storage usage during field testing.
     *
     * @return Count of all messages
     */
    @Query("SELECT COUNT(*) FROM messages")
    int getMessageCount();

    /**
     * Get count of undelivered messages.
     * Useful for statistics in your research paper.
     *
     * @return Count of messages where is_delivered = 0
     */
    @Query("SELECT COUNT(*) FROM messages WHERE is_delivered = 0")
    int getUndeliveredMessageCount();

    /**
     * Get messages ordered by priority (high to low) and TTL.
     * This query replicates the sorting done in MainActivity but at database level.
     * More efficient than sorting in memory.
     *
     * @param currentTime Current timestamp in milliseconds
     * @return List of non-expired, undelivered messages sorted by priority and TTL
     */
    @Query("SELECT * FROM messages WHERE ttl_timestamp > :currentTime AND is_delivered = 0 " +
            "ORDER BY priority DESC, ttl_timestamp DESC")
    List<Message> getMessagesToForwardSorted(long currentTime);
}
