package com.ebook.ftp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FTP_MainActivity";
    TextView showText;
    AppCompatButton startBtn;
    AppCompatButton stopBtn;
    CardView serverInfoCard;


    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.start_server);
        stopBtn = findViewById(R.id.end_server);
        showText = findViewById(R.id.show_ip);
        serverInfoCard = findViewById(R.id.server_info_card);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                        if (!entry.getValue()) {
                            Log.w(TAG, "Permission denied: " + entry.getKey());
                            allGranted = false;
                        }
                    }
                    if (allGranted) {
                        Log.d(TAG, "All required permissions granted.");
                        checkAndRequestAllFilesAccess();
                    } else {
                        Toast.makeText(this, "Please grant all required permissions.", Toast.LENGTH_LONG).show();
                    }
                });

        requestNeededPermissions();

        startBtn.setOnClickListener(view -> {
            Log.d(TAG, "Start button clicked.");
            // Ensure permissions before starting
            if (hasRequiredPermissions()) {
                Intent startIntent = new Intent(this, FtpService.class);
                startIntent.setAction(FtpService.ACTION_START);
                serverInfoCard.setVisibility(View.VISIBLE);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent); // Use this for Android 8+
                } else {
                    startService(startIntent);
                }
                updateIpAddress(); // Update UI
            } else {
                Toast.makeText(this, "Permissions not granted. Cannot start server.", Toast.LENGTH_SHORT).show();
                requestNeededPermissions(); // Ask again
            }
        });

        stopBtn.setOnClickListener(view -> {
            Log.d(TAG, "Stop button clicked.");
            Intent stopIntent = new Intent(this, FtpService.class);
            stopIntent.setAction(FtpService.ACTION_STOP);
            startService(stopIntent); // No need for startForegroundService to stop
            serverInfoCard.setVisibility(View.GONE);
            Toast.makeText(this, "FTP Server Stopping...", Toast.LENGTH_SHORT).show();
        });

        // Update IP address when activity resumes (in case Wi-Fi changes)
        updateIpAddress();
    }

    private void updateIpAddress() {
        String ipAddress = IpUtils.getLocalIpAddress();
        if (ipAddress != null) {
            String showIp = "ftp://" + ipAddress + ":2121";
            showText.setText(showIp);
            Log.d(TAG, "Current IP: " + showIp);
        } else {
            showText.setText("Could not get IP address.");
            Log.w(TAG, "Could not get local IP Address.");
        }
    }

    private boolean hasRequiredPermissions() {
        List<String> neededPermissions = getNeededPermissions();
        for (String perm : neededPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        // Also check MANAGE_EXTERNAL_STORAGE for R+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }


    /**
     * Requests necessary permissions from the user.
     *
     * This method first identifies which of the "needed" permissions (as defined by `getNeededPermissions()`)
     * have not yet been granted by the user.
     *
     * If there are any ungranted standard permissions, it launches a system dialog to request them.
     *
     * If all standard permissions are already granted, it proceeds to check and request
     * the "All Files Access" permission (if applicable and not granted).
     *
     * Logging is used to track the permission request process.
     */
    private void requestNeededPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        List<String> neededPermissions = getNeededPermissions();

        for (String perm : neededPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsToRequest);
            requestPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        } else {
            Log.d(TAG, "Standard permissions already granted, checking All Files Access.");
            checkAndRequestAllFilesAccess();
        }
    }

    /**
     * Determines and returns a list of permissions required by the application based on the Android API level.
     *
     * <p>The permissions included are:
     * <ul>
     *   <li>{@link Manifest.permission#POST_NOTIFICATIONS}: Required on Android 13 (API level 33) and above
     *       for the application to post notifications.</li>
     *   <li>{@link Manifest.permission#WAKE_LOCK}: Always required to prevent the processor from sleeping
     *       or the screen from dimming.</li>
     *   <li>{@link Manifest.permission#READ_EXTERNAL_STORAGE}: Required on Android versions below
     *       Android 11 (API level 30) to read from external storage.</li>
     *   <li>{@link Manifest.permission#WRITE_EXTERNAL_STORAGE}: Required on Android versions below
     *       Android 11 (API level 30) to write to external storage.</li>
     * </ul>
     *
     * @return A {@link List} of {@link String} objects, where each string represents a permission
     *         that the application needs to function correctly.
     */
    private List<String> getNeededPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        needed.add(Manifest.permission.WAKE_LOCK);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        return needed;
    }


    /**
     * Checks if the app has "All Files Access" (MANAGE_EXTERNAL_STORAGE) permission on Android R (API 30) and above.
     * If the permission is not granted, it guides the user to the system settings to grant it.
     *
     * This permission is required for apps that need broad access to shared storage.
     *
     * The method performs the following steps:
     * 1. Checks if the current Android version is R (API 30) or higher. If not, the method does nothing.
     * 2. If on Android R or higher, it checks if `Environment.isExternalStorageManager()` returns true,
     *    which indicates that the app already has the "All Files Access" permission.
     * 3. If the permission is not granted:
     *    a. Logs a message indicating that the permission is being requested.
     *    b. Displays a Toast message to the user explaining the need for the permission.
     *    c. Attempts to launch the specific app settings screen for "All Files Access" permission
     *       using `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
     *    d. If launching the app-specific settings fails (e.g., on some custom ROMs or due to unforeseen issues),
     *       it falls back to launching the general "Manage All Files Access" settings screen using
     *       `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`.
     * 4. If the permission is already granted, it logs a message indicating that.
     *
     * Note: The user will be taken out of the app to the system settings screen. The app should handle
     * the result of this permission request in `onActivityResult` or by re-checking the permission status
     * when the app resumes (e.g., in `onResume`).
     */
    private void checkAndRequestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d(TAG, "Requesting MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.");
                Toast.makeText(this, "Please grant 'All Files Access' permission", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting All Files Access settings", e);
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                Log.d(TAG, "MANAGE_APP_ALL_FILES_ACCESS_PERMISSION already granted.");
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        updateIpAddress();
    }

}