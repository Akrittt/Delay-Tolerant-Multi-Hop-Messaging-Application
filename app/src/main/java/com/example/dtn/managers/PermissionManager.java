package com.example.dtn.managers;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * PermissionManager - Manages all runtime permissions for the DTN app
 *
 * RESPONSIBILITIES:
 * - Check if permissions are granted
 * - Request permissions with explanations
 * - Handle permission denials
 * - Guide user to app settings for permanently denied permissions
 * - Provide callbacks for permission results
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final int REQUEST_PERMISSIONS = 2;

    private final Activity activity;
    private PermissionCallback callback;

    /**
     * Callback interface for permission results
     */
    public interface PermissionCallback {
        void onPermissionsGranted();
        void onPermissionsDenied(List<String> deniedPermissions);
        void onPermissionsPermanentlyDenied(List<String> permanentlyDenied);
    }

    public PermissionManager(Activity activity) {
        this.activity = activity;
    }

    /**
     * Set callback for permission results
     */
    public void setCallback(PermissionCallback callback) {
        this.callback = callback;
    }

    /**
     * Check if all required permissions are granted
     */
    public boolean hasAllRequiredPermissions() {
        // Location (critical for WiFi Direct and Bluetooth)
        boolean hasLocation = ContextCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasLocation) {
            Log.e(TAG, "❌ Missing ACCESS_FINE_LOCATION");
            return false;
        }

        // Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasBtScan = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasBtConnect = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

            if (!hasBtScan || !hasBtConnect) {
                Log.e(TAG, "❌ Missing Bluetooth permissions");
                return false;
            }
        }

        Log.d(TAG, "✅ All required permissions granted");
        return true;
    }

    /**
     * Check and request all necessary permissions
     */
    public void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // ==================== Location Permissions ====================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        } else {
            // Android 11 and below
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        // ==================== Bluetooth Permissions ====================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+) - New Bluetooth permissions
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }

        // ==================== WiFi Permissions ====================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        // ==================== Request Permissions ====================
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting " + permissionsToRequest.size() + " permissions");
            showPermissionExplanationDialog(permissionsToRequest);
        } else {
            Log.d(TAG, "All permissions already granted");
            if (callback != null) {
                callback.onPermissionsGranted();
            }
        }
    }

    /**
     * Show explanation dialog before requesting permissions
     */
    private void showPermissionExplanationDialog(List<String> permissions) {
        StringBuilder message = new StringBuilder();
        message.append("This DTN Mesh App requires the following permissions:\n\n");

        for (String permission : permissions) {
            if (permission.contains("LOCATION")) {
                message.append("📍 Location Access\n");
                message.append("   Required for Wi-Fi Direct and Bluetooth discovery\n\n");
            } else if (permission.contains("BLUETOOTH_SCAN")) {
                message.append("🔍 Bluetooth Scan\n");
                message.append("   Find nearby devices\n\n");
            } else if (permission.contains("BLUETOOTH_CONNECT")) {
                message.append("🔗 Bluetooth Connect\n");
                message.append("   Connect to devices\n\n");
            } else if (permission.contains("BLUETOOTH_ADVERTISE")) {
                message.append("📢 Bluetooth Advertise\n");
                message.append("   Make device discoverable\n\n");
            } else if (permission.contains("NEARBY_WIFI_DEVICES")) {
                message.append("📡 Nearby Wi-Fi Devices\n");
                message.append("   Wi-Fi Direct networking\n\n");
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle("Permissions Needed")
                .setMessage(message.toString())
                .setPositiveButton("Continue", (dialog, which) -> {
                    // Request permissions
                    ActivityCompat.requestPermissions(
                            activity,
                            permissions.toArray(new String[0]),
                            REQUEST_PERMISSIONS
                    );
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    Toast.makeText(activity, "Cannot proceed without permissions",
                            Toast.LENGTH_LONG).show();
                    activity.finish();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Handle permission request results
     * Call this from Activity's onRequestPermissionsResult()
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }

        boolean allGranted = true;
        List<String> deniedPermissions = new ArrayList<>();

        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                deniedPermissions.add(permissions[i]);
            }
        }

        if (allGranted) {
            Log.d(TAG, "✅ All permissions granted!");
            Toast.makeText(activity, "Permissions granted", Toast.LENGTH_SHORT).show();

            if (callback != null) {
                callback.onPermissionsGranted();
            }
        } else {
            Log.w(TAG, "⚠️ Some permissions denied: " + deniedPermissions.size());
            handleDeniedPermissions(deniedPermissions);
        }
    }

    /**
     * Handle denied permissions
     */
    private void handleDeniedPermissions(List<String> deniedPermissions) {
        // Check if any are permanently denied
        List<String> permanentlyDenied = new ArrayList<>();

        for (String permission : deniedPermissions) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                permanentlyDenied.add(permission);
            }
        }

        if (!permanentlyDenied.isEmpty()) {
            // Some permissions permanently denied
            Log.e(TAG, "❌ Permissions permanently denied: " + permanentlyDenied.size());
            showPermissionSettingsDialog(permanentlyDenied);

            if (callback != null) {
                callback.onPermissionsPermanentlyDenied(permanentlyDenied);
            }
        } else {
            // Permissions denied but can be requested again
            Log.w(TAG, "⚠️ Permissions denied but can retry");
            showPermissionRetryDialog();

            if (callback != null) {
                callback.onPermissionsDenied(deniedPermissions);
            }
        }
    }

    /**
     * Show retry dialog for denied permissions
     */
    private void showPermissionRetryDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Permissions Required")
                .setMessage("The app needs all permissions to function. Please grant them.")
                .setPositiveButton("Retry", (dialog, which) -> {
                    checkAndRequestPermissions();
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    Toast.makeText(activity, "Cannot proceed without permissions",
                            Toast.LENGTH_LONG).show();
                    activity.finish();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Show dialog to open app settings for permanently denied permissions
     */
    private void showPermissionSettingsDialog(List<String> permanentlyDenied) {
        StringBuilder message = new StringBuilder();
        message.append("You have permanently denied the following permissions:\n\n");

        for (String permission : permanentlyDenied) {
            String simpleName = permission.replace("android.permission.", "");
            message.append("• ").append(simpleName).append("\n");
        }

        message.append("\nPlease enable them in Settings to use this app.");

        new AlertDialog.Builder(activity)
                .setTitle("Enable Permissions in Settings")
                .setMessage(message.toString())
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    openAppSettings();
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    Toast.makeText(activity, "Cannot proceed without permissions",
                            Toast.LENGTH_LONG).show();
                    activity.finish();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Open app settings page
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
        intent.setData(uri);

        try {
            activity.startActivity(intent);
            Toast.makeText(activity, "Please enable all permissions", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Cannot open settings", e);
            Toast.makeText(activity, "Cannot open settings", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Get list of missing permissions
     */
    public List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();

        // Check location
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Check Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // Check WiFi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        return missing;
    }

    /**
     * Check if specific permission is granted
     */
    public boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}