package com.example.dtn.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {
    private static Logger instance;
    private File logFile;
    private static final String LOG_FILE_NAME = "dtn_log.txt";

    private Logger(Context context) {
        try {
            File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DTNLogs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            logFile = new File(logDir, LOG_FILE_NAME);
            if (!logFile.exists()) {
                logFile.createNewFile();
            } else {
                // Optional: Clear the log file on new app session
                new FileWriter(logFile, false).close();
            }
        } catch (IOException e) {
            Log.e("Logger", "Failed to initialize logger file", e);
        }
    }

    public static synchronized Logger getInstance(Context context) {
        if (instance == null) {
            instance = new Logger(context.getApplicationContext());
        }
        return instance;
    }

    public synchronized void logEvent(String eventDetails) {
        try (FileWriter writer = new FileWriter(logFile, true)) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String logEntry = timestamp + " | " + eventDetails + "\n";
            writer.append(logEntry);
            Log.d("Logger", "Logged: " + logEntry.trim());
        } catch (IOException e) {
            Log.e("Logger", "Failed to write to log file", e);
        }
    }
}