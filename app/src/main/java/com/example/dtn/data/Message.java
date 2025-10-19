package com.example.dtn.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.UUID;

@Entity(tableName = "messages")
public class Message implements Serializable { // Serializable is crucial for sending over sockets
    public static final int TYPE_DATA = 0;
    public static final int TYPE_ACK = 1;

    @PrimaryKey
    @NonNull
    public String message_id;
    public int message_type;
    public String source_id;
    public String destination_id;
    public byte[] encrypted_payload;
    public String checksum;
    public int priority; // 1 for HIGH, 0 for NORMAL
    public long ttl_timestamp;
    public int hop_count;
    public int copy_count;
    public boolean is_delivered;

    // Default constructor for Room
    public Message() {
        this.message_id = UUID.randomUUID().toString();
        this.message_type = TYPE_DATA; // Default to a data message
        this.is_delivered = false;
    }
}