package com.ebook.ftp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.security.Permission;

public class MainActivity extends AppCompatActivity {

    FTPServer ftpServer;
    private static final int PERMISSION_REQUEST_CODE = 1;
    TextView showText;


    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        File rootDir = Environment.getExternalStorageDirectory();

        AppCompatButton startBtn = findViewById(R.id.start_server);
        AppCompatButton stopBtn = findViewById(R.id.end_server);
        showText = findViewById(R.id.show_ip);

        String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();


        requestStoragePermission();
        startBtn.setOnClickListener(view -> {
            if (ftpServer != null && ftpServer.isRunning()) {
                Toast.makeText(this, "FTP Server is already running", Toast.LENGTH_SHORT).show();
                return;
            }

            new Thread(() -> {
                ftpServer = new FTPServer(2121, rootPath);
                try {
                    ftpServer.Start();
                } catch (SocketException e) {
                    Log.e("FTP", "Socket was closed unexpectedly", e);
                    runOnUiThread(() ->
                            Toast.makeText(this, "FTP stopped unexpectedly", Toast.LENGTH_SHORT).show()
                    );
                } catch (IOException e) {
                    Log.e("FTP", "IO Error", e);
                    runOnUiThread(() ->
                            Toast.makeText(this, "FTP failed to start", Toast.LENGTH_SHORT).show()
                    );
                }
            }).start();

            Toast.makeText(this, "FTP STARTED ON PORT 2121", Toast.LENGTH_SHORT).show();
            Log.d("FTP-IP", "Access this IP: ftp://" + IpUtils.getLocalIpAddress() + ":" + 2121);
            String showIp = "ftp://" + IpUtils.getLocalIpAddress() + ":" + 2121;
            showText.setText(showIp);

        });


        stopBtn.setOnClickListener(view -> {
            if(ftpServer != null && ftpServer.isRunning()){
                try {
                    ftpServer.stop();
                    showText.setText("");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Toast.makeText(this , "FTP STOPPED" , Toast.LENGTH_SHORT).show();
            }

        });

    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            if (!Environment.isExternalStorageManager()){
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            }, PERMISSION_REQUEST_CODE);

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode , String[] permission, int[] grantResults){
        super.onRequestPermissionsResult(requestCode , permission , grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE){
            boolean allGranted = true;
            for (int result : grantResults){
                if (result != PackageManager.PERMISSION_GRANTED){
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted){
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show();
            }
        }

    }

    private String findAccessibleStoragePath(){
        String[] possiblePaths={
                "/storage/emulated/0", Environment.getExternalStorageDirectory().getAbsolutePath(),
                getExternalFilesDir(null) != null? getExternalFilesDir(null).getAbsolutePath() : null,
                "/sdcard",
                getFilesDir().getAbsolutePath()

        };
        for(String path: possiblePaths){
            if (path != null) {

                File testDir = new File(path);
                if(testDir.exists() && testDir.canRead()){
                    Log.d("FTP", "Using path: " + path);
                    return path;
                }

            }
        }
        return null;
    }
}