package com.example.dtn.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Logger {
    private static Logger instance;
    private File logFile;
    private ExecutorService logExecutor;
    private static final String TAG = "DTNLogger";

    private static final ThreadLocal<SimpleDateFormat> dateFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private Logger(Context context) {
        logExecutor = Executors.newSingleThreadExecutor();

        try {
            File logDir = new File(context.getFilesDir(), "DTNLogs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            logFile = new File(logDir, "dtn_log_" + timestamp + ".txt");
            logFile.createNewFile();

            Log.d(TAG, "Log file created: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize logger file", e);
        }
    }

    public static synchronized Logger getInstance(Context context) {
        if (instance == null) {
            instance = new Logger(context.getApplicationContext());
        }
        return instance;
    }

    public void logEvent(String eventDetails) {
        if (logFile == null) {
            Log.e(TAG, "Log file not initialized, cannot log event");
            return;
        }

        logExecutor.execute(() -> {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                String timestamp = dateFormat.get().format(new Date());
                String logEntry = timestamp + " | " + eventDetails + "\n";
                writer.write(logEntry);
                writer.flush();
                Log.d(TAG, "Logged: " + logEntry.trim());
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log file", e);
            }
        });
    }

    public String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "Log file not initialized";
    }

    public void shutdown() {
        if (logExecutor != null) {
            logExecutor.shutdown();
        }
    }
}
