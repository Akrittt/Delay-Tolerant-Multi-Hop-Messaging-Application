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

/**
 * FIXED: Enhanced logging with rotation and log levels
 */
public class Logger {
    private static Logger instance;
    private File logFile;
    private File logDir;
    private ExecutorService logExecutor;
    private static final String TAG = "DTNLogger";

    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5 MB per file
    private static final int MAX_LOG_FILES = 3;

    // Log levels
    public static final int DEBUG = 0;
    public static final int INFO = 1;
    public static final int WARN = 2;
    public static final int ERROR = 3;
    private int currentLogLevel = DEBUG; // Log all by default

    private static final ThreadLocal<SimpleDateFormat> dateFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private Logger(Context context) {
        logExecutor = Executors.newSingleThreadExecutor();

        try {
            logDir = new File(context.getFilesDir(), "DTNLogs");
            if (!logDir.exists()) {
                boolean created = logDir.mkdirs();
                if (!created) {
                    Log.e(TAG, "Failed to create log directory");
                }
            }

            createLogFile();
            Log.d(TAG, "Logger initialized: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize logger", e);
        }
    }

    /**
     * FIXED: Create new log file with rotation check
     */
    private void createLogFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        logFile = new File(logDir, "dtn_log_" + timestamp + ".txt");

        if (!logFile.exists()) {
            boolean created = logFile.createNewFile();
            if (!created) {
                Log.e(TAG, "Failed to create log file");
            }
        }
    }

    /**
     * FIXED: Check and rotate log if size exceeded
     */
    private void checkAndRotate() {
        if (logFile != null && logFile.length() > MAX_LOG_SIZE) {
            try {
                // Archive old log
                File archivedLog = new File(logDir, logFile.getName() + ".old");
                logFile.renameTo(archivedLog);

                // Clean up old archives
                File[] logFiles = logDir.listFiles();
                if (logFiles != null) {
                    int count = 0;
                    for (File f : logFiles) {
                        if (f.getName().contains("dtn_log")) {
                            count++;
                        }
                    }

                    if (count > MAX_LOG_FILES) {
                        // Delete oldest file
                        File oldest = null;
                        long oldestTime = Long.MAX_VALUE;
                        for (File f : logFiles) {
                            if (f.getName().contains("dtn_log") && f.lastModified() < oldestTime) {
                                oldestTime = f.lastModified();
                                oldest = f;
                            }
                        }

                        if (oldest != null) {
                            boolean deleted = oldest.delete();
                            if (deleted) {
                                Log.d(TAG, "Deleted old log file: " + oldest.getName());
                            }
                        }
                    }
                }

                // Create new log file
                createLogFile();
                Log.d(TAG, "Log file rotated");

            } catch (IOException e) {
                Log.e(TAG, "Log rotation failed", e);
            }
        }
    }

    public static synchronized Logger getInstance(Context context) {
        if (instance == null) {
            instance = new Logger(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * FIXED: Log with level support
     */
    public void logEvent(String eventDetails) {
        logEvent(eventDetails, INFO);
    }

    public void logDebug(String eventDetails) {
        logEvent(eventDetails, DEBUG);
    }

    public void logInfo(String eventDetails) {
        logEvent(eventDetails, INFO);
    }

    public void logWarn(String eventDetails) {
        logEvent(eventDetails, WARN);
    }

    public void logError(String eventDetails) {
        logEvent(eventDetails, ERROR);
    }

    public void logError(String eventDetails, Exception e) {
        logEvent(eventDetails + " | Exception: " + e.getMessage(), ERROR);
    }

    /**
     * FIXED: Internal log with level filtering
     */
    private void logEvent(String eventDetails, int level) {
        if (level < currentLogLevel) {
            return; // Don't log if below current level
        }

        if (logFile == null) {
            Log.e(TAG, "Log file not initialized");
            return;
        }

        logExecutor.execute(() -> {
            try {
                checkAndRotate(); // Check before writing

                String levelStr = getLevelString(level);
                BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));

                String timestamp = dateFormat.get().format(new Date());
                String logEntry = String.format("%s | %s | %s\n", timestamp, levelStr, eventDetails);

                writer.write(logEntry);
                writer.flush();
                writer.close();

                Log.d(TAG, logEntry.trim());

            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log file", e);
            }
        });
    }

    private String getLevelString(int level) {
        switch (level) {
            case DEBUG:
                return "DEBUG";
            case INFO:
                return "INFO";
            case WARN:
                return "WARN";
            case ERROR:
                return "ERROR";
            default:
                return "UNKNOWN";
        }
    }

    public void setLogLevel(int level) {
        this.currentLogLevel = level;
        Log.d(TAG, "Log level set to: " + getLevelString(level));
    }

    public String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "Log file not initialized";
    }

    public String getLogDir() {
        return logDir != null ? logDir.getAbsolutePath() : "Log directory not initialized";
    }

    public void shutdown() {
        if (logExecutor != null) {
            logExecutor.shutdown();
            Log.d(TAG, "Logger shutdown complete");
        }
    }
}
