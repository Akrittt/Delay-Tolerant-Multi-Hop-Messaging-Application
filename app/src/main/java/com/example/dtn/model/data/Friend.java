package com.example.dtn.model.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Friend entity represents devices that have been manually added to the friends list.
 * Used for multi-hop routing heuristics based on encounter history.
 */
@Entity(tableName = "friends")
public class Friend {

    /**
     * The unique device identifier (MAC address or device name)
     * Example: "02:00:00:00:00:00" or "Pixel 7 Pro"
     */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "device_id")
    public String deviceId;

    /**
     * User-defined friendly name for the device
     * Example: "John's Phone"
     */
    @ColumnInfo(name = "friendly_name")
    public String friendlyName;

    /**
     * Timestamp (in milliseconds) of the last successful connection with this friend
     * Used for multi-hop routing decisions
     */
    @ColumnInfo(name = "last_encountered_timestamp")
    public long lastEncounteredTimestamp;

    /**
     * No-argument constructor required by Room
     */
    public Friend() {
        this.deviceId = "";
        this.friendlyName = "";
        this.lastEncounteredTimestamp = 0;
    }

    /**
     * Convenience constructor for creating Friend instances
     * @param deviceId The unique device identifier
     * @param friendlyName User-defined name for the device
     * @param lastEncounteredTimestamp When we last connected to this friend
     */
    @Ignore
    public Friend(@NonNull String deviceId, String friendlyName, long lastEncounteredTimestamp) {
        this.deviceId = deviceId;
        this.friendlyName = friendlyName;
        this.lastEncounteredTimestamp = lastEncounteredTimestamp;
    }

    /**
     * Convenience constructor with timestamp set to 0 (never encountered)
     * @param deviceId The unique device identifier
     * @param friendlyName User-defined name for the device
     */
    @Ignore
    public Friend(@NonNull String deviceId, String friendlyName) {
        this.deviceId = deviceId;
        this.friendlyName = friendlyName;
        this.lastEncounteredTimestamp = 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "Friend{" +
                "deviceId='" + deviceId + '\'' +
                ", friendlyName='" + friendlyName + '\'' +
                ", lastEncounteredTimestamp=" + lastEncounteredTimestamp +
                '}';
    }
}
