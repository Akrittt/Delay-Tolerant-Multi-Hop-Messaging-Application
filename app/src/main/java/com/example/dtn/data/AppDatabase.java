package com.example.dtn.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Message.class, Friend.class}, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG = "AppDatabase";
    private static final String DATABASE_NAME = "dtn_database";

    public abstract MessageDao messageDao();
    public abstract FriendDao friendDao();

    private static volatile AppDatabase INSTANCE;

    /**
     * Thread-safe singleton pattern for database access
     * Prevents multiple database instances which could cause memory leaks
     */
    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = buildDatabase(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Build the Room database with appropriate configuration
     */
    private static AppDatabase buildDatabase(final Context context) {
        return Room.databaseBuilder(
                        context,
                        AppDatabase.class,
                        DATABASE_NAME)
                .fallbackToDestructiveMigration() // For testing/development - see warning below
                .addCallback(roomCallback) // Optional: Add database creation/open callbacks
                .build();
    }

    /**
     * Optional: Database callback for initialization or cleanup
     * Useful for logging database events during testing
     */
    private static final RoomDatabase.Callback roomCallback = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            Log.d(TAG, "Database created - Version " + db.getVersion());
        }

        @Override
        public void onOpen(@NonNull SupportSQLiteDatabase db) {
            super.onOpen(db);
            Log.d(TAG, "Database opened - Version " + db.getVersion());
        }
    };

    /**
     * Optional: Method to close database (useful for testing)
     * Call this only during testing or app shutdown
     */
    public static void closeDatabase() {
        if (INSTANCE != null && INSTANCE.isOpen()) {
            INSTANCE.close();
            INSTANCE = null;
            Log.d(TAG, "Database closed");
        }
    }
}
