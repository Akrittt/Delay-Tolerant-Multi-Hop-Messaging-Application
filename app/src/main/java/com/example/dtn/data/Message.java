package com.example.dtn.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.UUID;

@Entity(tableName = "messages")
public class Message implements Serializable { // Serializable is crucial for sending over sockets

    @PrimaryKey
    @NonNull
    public String message_id;

    public String source_id;
    public String destination_id;
    public byte[] encrypted_payload;
    public String checksum;
    public int priority; // 1 for HIGH, 0 for NORMAL
    public long ttl_timestamp;
    public int hop_count;
    public int copy_count;

    // Default constructor for Room
    public Message() {
        this.message_id = UUID.randomUUID().toString();
    }
}