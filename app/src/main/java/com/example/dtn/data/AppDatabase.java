package com.example.dtn.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Message.class, Friend.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();
    public abstract FriendDao friendDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "dtn_database")
                            .fallbackToDestructiveMigration() // Easiest for project-level changes
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}