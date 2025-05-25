package com.ebook.ftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;

public class FtpService extends Service {

    private static final String TAG = "FTP_Service";
    private static final String CHANNEL_ID = "FtpServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "com.ebook.ftp.ACTION_START";
    public static final String ACTION_STOP = "com.ebook.ftp.ACTION_STOP";

    private FTPServer ftpServer;

//    FTP server runs in separate thread to avoid blocking the main UI thread
    private Thread serverThread;

//    Prevents WiFi from going to sleep mode while FTP server is running
    private WifiManager.WifiLock wifiLock;

    //Keeps CPU awake
    private PowerManager.WakeLock wakeLock; // Optional, but can help further

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
        Log.d(TAG, "FTP Service Created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_START:
                    startFtpServer();
                    break;
                case ACTION_STOP:
                    stopFtpServer();
                    break;
            }
        }
        return START_STICKY;
    }

    private void startFtpServer() {
        if (ftpServer != null && ftpServer.isRunning()) {
            Log.w(TAG, "FTP Server is already running.");
            Toast.makeText(this, "FTP Server is already running", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        String ipAddress = IpUtils.getLocalIpAddress();
        String notificationText = (ipAddress != null)
                ? "FTP Server Running at ftp://" + ipAddress + ":2121"
                : "FTP Server Running...";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FTP Server Active")
                .setContentText(notificationText)
                .setSmallIcon(R.drawable.ftp)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes it non-dismissible
                .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "Service started in foreground.");

        serverThread = new Thread(() -> {
            try {
                String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
                ftpServer = new FTPServer(2121, rootPath);
                Log.i(TAG, "Starting FTP Server on port 2121 with root: " + rootPath);
                ftpServer.Start();
                Log.i(TAG, "FTP Server Start() method finished.");

            } catch (SocketException e) {
                Log.e(TAG, "Socket was closed, server likely stopped: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "FTP Server failed to start or run: " + e.getMessage(), e);
            } finally {
                Log.d(TAG, "FTP Server thread ending, cleaning up service.");
            }
        });
        serverThread.start();
        Toast.makeText(this, "FTP Server Started", Toast.LENGTH_SHORT).show();
    }

    private void stopFtpServer() {
        Log.d(TAG, "Attempting to stop FTP Server.");
        new Thread(() -> {
            try {
                if (ftpServer != null && ftpServer.isRunning()) {
                    ftpServer.stop();
                    ftpServer = null;
                    Log.i(TAG, "FTP Server Stopped.");
                } else {
                    Log.w(TAG, "FTP Server was not running or null.");
                }
            } catch (IOException e) {
                Log.e(TAG, "Error stopping FTP Server: " + e.getMessage(), e);
            } finally {
                if (serverThread != null) {
                    serverThread.interrupt(); // Attempt to interrupt if blocked
                    serverThread = null;
                }
                stopForeground(true); // Remove notification
                stopSelf(); // Stop the service itself
            }
        }).start();

        Toast.makeText(this, "FTP Server Stopped", Toast.LENGTH_SHORT).show();
    }


    /**
     * Creates a notification channel for the FTP server service.
     * This method is only executed if the Android version is Oreo (API level 26) or higher,
     * as notification channels were introduced in that version.
     * The channel is configured with a low importance to minimize user interruption.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "FTP Server Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * Acquires the necessary system locks to ensure the FTP server can run reliably
     * in the background.
     *
     * This method attempts to acquire:
     * 1. **Wi-Fi Lock (WIFI_MODE_FULL_HIGH_PERF):**
     *    - Prevents the Wi-Fi radio from turning off or going into a low-power state
     *      while the FTP server is active. This is crucial for maintaining network
     *      connectivity.
     *    - The lock is set to not be reference-counted, meaning its lifecycle is
     *      managed explicitly by this service (acquired here and released in `releaseLocks`).
     * 2. **Partial Wake Lock (PARTIAL_WAKE_LOCK):**
     *    - Ensures that the CPU continues to run even if the screen turns off.
     *      This allows the FTP server to continue processing requests and transferring
     *      files in the background.
     *    - Similar to the Wi-Fi lock, it's not reference-counted.
     *    - **Note:** The `wakeLock.acquire()` call is currently commented out. This
     *      suggests it might be acquired conditionally elsewhere or was part of a
     *      previous implementation. If background operation without the screen on is
     *      required, this line should be uncommented.
     *
     * Logs are generated to indicate the success or failure of acquiring each lock.
     */
    private void acquireLocks() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FtpService:WifiLock");
            wifiLock.setReferenceCounted(false); // We manage its lifecycle
            wifiLock.acquire();
            Log.d(TAG, "WifiLock acquired.");
        } else {
            Log.w(TAG, "WifiManager not available.");
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FtpService:WakeLock");
            wakeLock.setReferenceCounted(false);
            // wakeLock.acquire();
            // Log.d(TAG, "WakeLock acquired.");
        }
    }

    // Release locks when service is destroyed
    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
            Log.d(TAG, "WifiLock released.");
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.d(TAG, "WakeLock released.");
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "FTP Service Destroyed.");
        if (ftpServer != null && ftpServer.isRunning()) {
            stopFtpServer();
        }
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}