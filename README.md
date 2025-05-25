# Android FTP Server

A lightweight, native FTP server implementation for Android devices that allows file sharing over local networks.

## Overview

This Android application implements a fully functional FTP server that runs directly on Android devices. It enables users to access and manage files on their Android device from any FTP client on the same network.

## Features

- **Simple User Interface**: Easy-to-use interface with start/stop controls
- **Background Service**: Runs as a foreground service with notification
- **Secure Authentication**: Basic username/password authentication
- **File Operations**: Supports standard FTP operations:
  - File upload (STOR)
  - File download (RETR)
  - Directory listing (LIST)
  - Directory navigation (CWD)
  - File size query (SIZE)
- **Passive Mode Support**: Implements FTP passive mode for better compatibility with clients behind firewalls
- **Power Management**: Utilizes WiFi locks to prevent connection loss when device is idle
- **Android 11+ Support**: Implements proper permission handling for modern Android versions

## Technical Implementation

### Components

1. **MainActivity**: User interface for controlling the FTP server
2. **FtpService**: Android service that manages the FTP server lifecycle
3. **FTPServer**: Core server implementation that listens for client connections
4. **ClientHandler**: Handles individual client connections and FTP protocol commands
5. **IpUtils**: Utility class for network-related operations

### Permissions

The application requires the following permissions:
- `INTERNET`: For network socket operations
- `ACCESS_WIFI_STATE`: To obtain the device's IP address
- `ACCESS_NETWORK_STATE`: To monitor network connectivity
- `WAKE_LOCK`: To prevent the device from sleeping while the server is running
- `FOREGROUND_SERVICE`: To run as a foreground service
- `MANAGE_EXTERNAL_STORAGE`: For full file access on Android 11+
- `READ_MEDIA_*`: For media file access on modern Android versions

## Usage

1. Launch the application
2. Grant all requested permissions (including "All Files Access" on Android 11+)
3. Press the "Start Server" button to start the FTP server
4. Use the displayed FTP address (e.g., ftp://192.168.1.100:2121) to connect from an FTP client
5. Login with username "admin" and password "admin"
6. Press the "Stop Server" button when finished

## FTP Client Connection

Connect to the server using any standard FTP client with these settings:
- **Host**: The IP address shown in the app (e.g., 192.168.1.100)
- **Port**: 2121
- **Username**: admin
- **Password**: admin
- **Mode**: Passive

## Development

### Project Structure

```
com.ebook.ftp/
├── MainActivity.java       # Main UI and permission handling
├── FtpService.java         # Android service implementation
├── FTPServer.java          # Core server implementation
├── ClientHandler.java      # FTP protocol and client handling
└── IpUtils.java            # Network utility functions
```

### Build Requirements

- Android Studio Flamingo or newer
- Minimum SDK: API 21 (Android 5.0)
- Target SDK: API 33 (Android 13)

## Security Considerations

This application uses fixed credentials (username: "admin", password: "admin") and does not implement encryption. It is intended for use on trusted local networks only and should not be exposed to the internet.


