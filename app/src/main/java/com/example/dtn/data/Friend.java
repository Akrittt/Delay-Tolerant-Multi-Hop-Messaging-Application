package com.example.dtn.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "friends")
public class Friend {
    @PrimaryKey
    @NonNull
    public String deviceId; // The unique device name/address, e.g., "Pixel 7 Pro"

    public String friendlyName; // A user-defined name, e.g., "John's Phone"

    public long lastEncounteredTimestamp; // When we last connected to this friend


}
