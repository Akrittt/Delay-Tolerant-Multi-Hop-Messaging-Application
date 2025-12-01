package com.example.dtn.model.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.dtn.model.data.Friend;
import com.example.dtn.model.data.FriendDao;
import com.example.dtn.model.data.Message;
import com.example.dtn.model.data.MessageDao;

@Database(entities = {Message.class, Friend.class}, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG = "AppDatabase";
    private static final String DATABASE_NAME = "dtn_database";

    public abstract MessageDao messageDao();
    public abstract FriendDao friendDao();

    private static volatile AppDatabase INSTANCE;

    /**
     * FIXED: Migration from version 4 to 5
     * Add any schema changes here
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            Log.d(TAG, "Migrating database from version 4 to 5");
            Log.d(TAG, "Migration 4->5 complete");
        }
    };

    /**
     * FIXED: Migration from version 3 to 4
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            Log.d(TAG, "Migrating database from version 3 to 4");
            // Add any version 3 to 4 changes here
            Log.d(TAG, "Migration 3->4 complete");
        }
    };

    /**
     * Thread-safe singleton pattern for database access
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
     * FIXED: Build with proper migrations instead of destructive fallback
     */
    private static AppDatabase buildDatabase(final Context context) {
        return Room.databaseBuilder(
                        context,
                        AppDatabase.class,
                        DATABASE_NAME)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5) // FIXED: Add migrations
                .fallbackToDestructiveMigrationOnDowngrade() // Only on downgrade
                .addCallback(roomCallback)
                .build();
    }

    /**
     * Database callback for initialization or cleanup
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
     * Close database (for testing)
     */
    public static void closeDatabase() {
        if (INSTANCE != null && INSTANCE.isOpen()) {
            INSTANCE.close();
            INSTANCE = null;
            Log.d(TAG, "Database closed");
        }
    }
}