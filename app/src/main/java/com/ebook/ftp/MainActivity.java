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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
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

    // Modern way to handle permission requests
    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.start_server);
        stopBtn = findViewById(R.id.end_server);
        showText = findViewById(R.id.show_ip);

        // --- Setup Permission Launcher ---
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
                        checkAndRequestAllFilesAccess(); // Now check for All Files Access
                    } else {
                        Toast.makeText(this, "Please grant all required permissions.", Toast.LENGTH_LONG).show();
                        // You might want to disable buttons or show a more specific message
                    }
                });

        // --- Request Permissions on Start ---
        requestNeededPermissions();

        // --- Button Click Listeners ---
        startBtn.setOnClickListener(view -> {
            Log.d(TAG, "Start button clicked.");
            // Ensure permissions before starting
            if (hasRequiredPermissions()) {
                Intent startIntent = new Intent(this, FtpService.class);
                startIntent.setAction(FtpService.ACTION_START);
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
            showText.setText(""); // Clear UI
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
            checkAndRequestAllFilesAccess(); // If standard perms exist, check the special one
        }
    }

    private List<String> getNeededPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
            // On Tiramisu+, you might still need media permissions if NOT using All Files Access
            // but since we *are* aiming for All Files Access, POST_NOTIFICATIONS is key.
        }

        // We aim for All Files Access, but WAKE_LOCK is separate.
        needed.add(Manifest.permission.WAKE_LOCK);

        // You no longer need READ/WRITE_EXTERNAL_STORAGE if you get MANAGE_EXTERNAL_STORAGE
        // but it doesn't hurt to ask on older versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        return needed;
    }


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

    // You might need to override onResume to re-check permissions if the user
    // comes back from settings.
    @Override
    protected void onResume() {
        super.onResume();
        updateIpAddress(); // Update IP on resume
        // Optionally re-check permissions here
    }

    // You no longer need onRequestPermissionsResult if using ActivityResultLauncher
}