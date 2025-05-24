package com.ebook.ftp;

import android.util.Log; // <-- Make sure this import is added

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat; // For LIST format
import java.util.Date; // For LIST format
import java.util.Locale; // For LIST format
import java.util.StringTokenizer;
import java.util.TimeZone; // For LIST format


public class ClientHandler implements Runnable {

    // Added a TAG for logging
    private static final String TAG = "FTP_ClientHandler";

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private final String username;
    private final String password;

    private boolean isLoggedIn = false;
    private String rootDir;
    private String currentDir;

    private ServerSocket dataServerSocket;
    private Socket dataSocket;

    public ClientHandler(Socket clientSocket, String username, String password, String rootDir) throws IOException {
        this.controlSocket = clientSocket;
        this.username = username;
        this.password = password;
        this.rootDir = new File(rootDir).getCanonicalPath(); // Use canonical path for consistency
        this.currentDir = this.rootDir;

        reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));

        Log.d(TAG, "ClientHandler created for " + clientSocket.getRemoteSocketAddress() + " with root: " + this.rootDir);
    }

    @Override
    public void run() {
        try {
            sendResponse("220 Welcome to the Android FTP server");
            String line;
            String user = null;

            try {
                while ((line = reader.readLine()) != null) { // Check for null to handle disconnects
                    Log.i(TAG, "CMD: " + line); // Log received command
                    String command = "";
                    String argument = "";

                    StringTokenizer tokenizer = new StringTokenizer(line);
                    if (tokenizer.hasMoreTokens()) {
                        command = tokenizer.nextToken().toUpperCase();
                        if (tokenizer.hasMoreTokens()) {
                            argument = line.substring(command.length()).trim();
                        }
                    } else {
                        // Handle empty lines if necessary, or just continue
                        continue;
                    }

                    switch (command) {
                        case "USER":
                            user = argument;
                            sendResponse("331 User name ok, need password");
                            break;
                        case "PASS":
                            if (user != null && user.equals(username) && argument.equals(password)) {
                                isLoggedIn = true;
                                Log.i(TAG, "User " + user + " logged in.");
                                sendResponse("230 User logged in, proceed");
                            } else {
                                Log.w(TAG, "Login incorrect for user: " + user);
                                sendResponse("530 Login incorrect");
                                isLoggedIn = false; // Ensure not logged in
                            }
                            break;

                        case "PWD":
                        case "XPWD": // Some clients use XPWD
                            if (!checkLoggedIn()) break;
                            String displayPath = currentDir.replace(rootDir, "");
                            if (displayPath.isEmpty()) displayPath = "/";
                            sendResponse("257 \"" + displayPath + "\" is the current directory.");
                            break;

                        case "CWD":
                            if (!checkLoggedIn()) break;
                            changeWorkingDirectory(argument);
                            break;

                        case "PASV":
                            if (!checkLoggedIn()) break;
                            enterPassiveMode();
                            break;

                        case "LIST":
                        case "NLST": // Handle NLST too
                            if (!checkLoggedIn()) break;
                            sendDirectoryListing(argument); // Pass argument (may contain path or options)
                            break;

                        case "RETR":
                            if (!checkLoggedIn()) break;
                            sendFile(argument);
                            break;

                        case "STOR":
                            if (!checkLoggedIn()) break;
                            receiveFile(argument);
                            break;

                        case "QUIT":
                            sendResponse("221 Goodbye.");
                            controlSocket.close();
                            return; // Exit the run method

                        case "FEAT":
                            sendResponse("211-Features:");
                            sendResponse(" UTF8");
                            sendResponse(" PASV");
                            sendResponse(" MLSD"); // Modern clients often prefer MLSD
                            sendResponse(" SIZE");
                            sendResponse("211 End");
                            break;

                        case "TYPE":
                            // We mainly support Binary (I), but acknowledge A too.
                            if (argument.equalsIgnoreCase("I")) {
                                sendResponse("200 Type set to I (Binary)");
                            } else if (argument.equalsIgnoreCase("A")) {
                                sendResponse("200 Type set to A (ASCII)");
                            } else {
                                sendResponse("504 Type not supported.");
                            }
                            break;

                        case "SYST":
                            sendResponse("215 UNIX Type: L8");
                            break;

                        case "OPTS":
                            if (argument.toUpperCase().startsWith("UTF8 ON")) {
                                sendResponse("200 UTF8 set to on");
                            } else {
                                sendResponse("501 Option not understood");
                            }
                            break;

                        case "SIZE": // Handle SIZE command
                            if (!checkLoggedIn()) break;
                            handleSize(argument);
                            break;

                        case "NOOP":
                            sendResponse("200 NOOP command successful");
                            break;

                        default:
                            Log.w(TAG, "Command not implemented: " + command + " " + argument);
                            sendResponse("502 Command not implemented.");
                            break;
                    }
                }
                Log.i(TAG, "Client disconnected (readLine returned null).");

            } catch (SocketException e) {
                Log.w(TAG, "Client connection aborted: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "IOException in command loop: " + e.getMessage(), e);
            }

        } catch (IOException e) {
            Log.e(TAG, "IOException in ClientHandler setup: " + e.getMessage(), e);
        } finally {
            Log.d(TAG, "Cleaning up resources for client.");
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (controlSocket != null && !controlSocket.isClosed()) controlSocket.close();
                closeDataConnection(); // Ensure data connections are closed
            } catch (IOException e) {
                Log.e(TAG, "Error during final cleanup: " + e.getMessage(), e);
            }
        }
    }

    // --- Helper to build and validate file paths ---
    private File buildFile(String path) throws IOException {
        String effectivePath;
        File baseDir;

        if (path.startsWith("/")) {
            // Absolute path: relative to rootDir
            effectivePath = path.substring(1);
            baseDir = new File(rootDir);
        } else {
            // Relative path: relative to currentDir
            effectivePath = path;
            baseDir = new File(currentDir);
        }

        File targetFile = new File(baseDir, effectivePath);
        String canonicalPath = targetFile.getCanonicalPath();

        // Security Check: Ensure the path doesn't go above rootDir.
        // Also check if rootDir itself is being accessed (which is allowed).
        if (!canonicalPath.startsWith(rootDir) && !canonicalPath.equals(rootDir)) {
            Log.w(TAG, "Security Alert: Attempted access outside root: " + canonicalPath + " (Original: " + path + ")");
            throw new IOException("Access denied - Path outside root directory.");
        }

        return targetFile;
    }

    // --- File Operations ---

    private void receiveFile(String filename) throws IOException {
        if (dataServerSocket == null) {
            sendResponse("425 Use PASV first");
            return;
        }

        File file;
        try {
            file = buildFile(filename);
        } catch (IOException e) {
            sendResponse("550 " + e.getMessage());
            return;
        }

        Log.d(TAG, "Attempting to receive (STOR): " + file.getAbsolutePath());

        // Check write permissions
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Log.w(TAG, "STOR failed: Cannot create parent directory: " + parent.getAbsolutePath());
            sendResponse("550 Cannot create directory.");
            closeDataConnection();
            return;
        }
        if (!parent.canWrite()) {
            Log.w(TAG, "STOR failed: Permission denied (canWrite=false) for directory: " + parent.getAbsolutePath());
            sendResponse("550 Permission denied: Cannot write to directory.");
            closeDataConnection();
            return;
        }
        if (file.exists() && !file.canWrite()) {
            Log.w(TAG, "STOR failed: Permission denied (canWrite=false) for existing file: " + file.getAbsolutePath());
            sendResponse("550 Permission denied: File exists and is not writable.");
            closeDataConnection();
            return;
        }

        try {
            dataSocket = dataServerSocket.accept();
            sendResponse("150 Opening data connection for " + filename);

            Log.d(TAG, "STOR: Opening FileOutputStream for: " + file.getAbsolutePath());
            try (BufferedInputStream in = new BufferedInputStream(dataSocket.getInputStream());
                 FileOutputStream fos = new FileOutputStream(file)) {

                byte[] buffer = new byte[8192]; // Increased buffer size
                int read;
                while ((read = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                Log.i(TAG, "STOR: Received file: " + file.getName());
                sendResponse("226 Transfer Completed.");
            } catch (IOException e) {
                Log.e(TAG, "IOException during STOR transfer: " + file.getAbsolutePath(), e);
                sendResponse("426 Data connection error or transfer aborted. " + e.getMessage());
            }

        } catch (IOException e) {
            Log.e(TAG, "IOException accepting STOR data connection: " + e.getMessage(), e);
            sendResponse("426 Data connection error or transfer aborted. " + e.getMessage());
        } finally {
            closeDataConnection();
        }
    }


    private void sendFile(String filename) throws IOException {
        if (dataServerSocket == null) {
            sendResponse("425 Use PASV first");
            return;
        }

        File file;
        try {
            file = buildFile(filename);
        } catch (IOException e) {
            sendResponse("550 " + e.getMessage());
            return;
        }

        Log.d(TAG, "Attempting to send (RETR): " + file.getAbsolutePath());

        if (!file.exists() || !file.isFile()) {
            Log.w(TAG, "RETR failed: File not found or not a file: " + file.getAbsolutePath());
            sendResponse("550 File not found or not a regular file.");
            return;
        }

        if (!file.canRead()) {
            Log.w(TAG, "RETR failed: Permission denied (canRead=false): " + file.getAbsolutePath());
            sendResponse("550 Permission denied: Cannot read file on server.");
            return;
        }

        try {
            dataSocket = dataServerSocket.accept();
            sendResponse("150 Opening BINARY mode data connection for " + file.getName() + " (" + file.length() + " bytes).");

            Log.d(TAG, "RETR: Opening FileInputStream for: " + file.getAbsolutePath());
            try (BufferedOutputStream out = new BufferedOutputStream(dataSocket.getOutputStream());
                 FileInputStream fis = new FileInputStream(file)) {

                byte[] buffer = new byte[8192]; // Increased buffer size
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                Log.i(TAG, "RETR: Sent file: " + file.getName());
                sendResponse("226 Transfer complete.");

            } catch (IOException e) {
                Log.e(TAG, "IOException during RETR transfer: " + file.getAbsolutePath(), e);
                sendResponse("426 Data connection error or transfer aborted. " + e.getMessage());
            }

        } catch (IOException e) {
            Log.e(TAG, "IOException accepting RETR data connection: " + e.getMessage(), e);
            sendResponse("426 Data connection error or transfer aborted. " + e.getMessage());
        } finally {
            closeDataConnection();
        }
    }

    private void sendDirectoryListing(String argument) throws IOException {
        if (dataServerSocket == null) {
            sendResponse("425 Use PASV first");
            return;
        }

        try {
            dataSocket = dataServerSocket.accept();
            sendResponse("150 Opening ASCII mode data connection for file list.");

            BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream()));
            File dir = new File(currentDir);
            File[] files = dir.listFiles();

            // SimpleDateFormat for standard LIST format
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault()); // Use server's timezone

            if (files != null) {
                Log.d(TAG, "Listing directory: " + dir.getAbsolutePath() + " (" + files.length + " items)");
                for (File f : files) {
                    // Skip if cannot read (though listFiles might already filter some)
                    if (!f.canRead()) continue;

                    String perms = (f.isDirectory() ? "d" : "-") +
                            (f.canRead() ? "r" : "-") +
                            (f.canWrite() ? "w" : "-") +
                            (f.canExecute() ? "x" : "-") + // 'x' might not be accurate for Android FS
                            "------"; // Simplified permissions
                    long size = f.length();
                    String dateStr = sdf.format(new Date(f.lastModified()));
                    String line = String.format(Locale.US, "%s 1 ftp ftp %15d %s %s\r\n",
                            perms, size, dateStr, f.getName());
                    dataWriter.write(line);
                }
            } else {
                Log.w(TAG, "LIST failed: listFiles() returned null for: " + dir.getAbsolutePath());
            }
            dataWriter.flush();
            Log.d(TAG, "LIST: Directory listing sent.");
            sendResponse("226 Transfer Completed.");

        } catch (IOException e) {
            Log.e(TAG, "IOException during LIST: " + e.getMessage(), e);
            sendResponse("426 Data connection error or transfer aborted.");
        } finally {
            closeDataConnection();
        }
    }

    private void handleSize(String filename) throws IOException {
        File file;
        try {
            file = buildFile(filename);
        } catch (IOException e) {
            sendResponse("550 " + e.getMessage());
            return;
        }

        if (file.exists() && file.isFile() && file.canRead()) {
            sendResponse("213 " + file.length());
        } else {
            sendResponse("550 Could not get file size.");
        }
    }

    // --- Connection and Directory Management ---

    private void enterPassiveMode() throws IOException {
        closeDataConnection(); // Close any existing connections first

        dataServerSocket = new ServerSocket(0); // 0 means assign any free port
        int port = dataServerSocket.getLocalPort();
        Log.d(TAG, "Passive mode started on port: " + port);

        String ip = controlSocket.getLocalAddress().getHostAddress();
        // Handle potential IPv6 addresses (though unlikely here, good practice)
        if (ip == null || ip.contains(":")) {
            Log.e(TAG, "Could not get valid IPv4 address for PASV.");
            sendResponse("425 Can't open data connection (IP Address Error).");
            closeDataConnection();
            return;
        }
        String ipFormatted = ip.replace('.', ',');

        int p1 = port / 256;
        int p2 = port % 256;

        sendResponse("227 Entering Passive Mode (" + ipFormatted + "," + p1 + "," + p2 + ").");
    }

    private void changeWorkingDirectory(String dir) throws IOException {
        File newDir;

        if (dir.equals("..")) {
            File current = new File(currentDir);
            File parent = current.getParentFile();
            // Ensure we don't go above the rootDir
            if (parent != null && parent.getCanonicalPath().length() >= rootDir.length() && parent.getCanonicalPath().startsWith(rootDir)) {
                currentDir = parent.getCanonicalPath();
                Log.d(TAG, "CWD to parent: " + currentDir);
                sendResponse("250 Directory successfully changed.");
            } else {
                currentDir = rootDir; // Or stay, or send error
                Log.d(TAG, "CWD .. : Already at root. Staying at " + currentDir);
                sendResponse("250 Directory changed (at root).");
            }
            return;
        } else if (dir.equals("/") || dir.equals("~")) {
            currentDir = rootDir;
            Log.d(TAG, "CWD to root: " + currentDir);
            sendResponse("250 Directory successfully changed.");
            return;
        }

        try {
            newDir = buildFile(dir);
            if (newDir.exists() && newDir.isDirectory() && newDir.canRead()) {
                currentDir = newDir.getCanonicalPath();
                String displayPath = currentDir.replace(rootDir, "");
                if (displayPath.isEmpty()) displayPath = "/";
                Log.d(TAG, "CWD to: " + currentDir);
                sendResponse("250 Directory successfully changed to " + displayPath);
            } else {
                Log.w(TAG, "CWD failed. Path: " + newDir.getAbsolutePath() + " | Exists: " + newDir.exists() + " | IsDir: " + newDir.isDirectory() + " | CanRead: " + (newDir.exists() ? newDir.canRead() : "N/A"));
                sendResponse("550 Failed to change directory: Not found, not a directory, or permission denied.");
            }
        } catch (IOException e) {
            Log.e(TAG, "CWD failed with exception for dir: " + dir, e);
            sendResponse("550 Failed to change directory: " + e.getMessage());
        }
    }


    // --- Utilities ---

    private boolean checkLoggedIn() throws IOException {
        if (!isLoggedIn) {
            sendResponse("530 Not logged in.");
            return false;
        }
        return true;
    }

    private void sendResponse(String response) throws IOException {
        try {
            if (writer != null && controlSocket != null && !controlSocket.isClosed()) {
                Log.d(TAG, "RSP: " + response); // Log sent response
                writer.write(response + "\r\n");
                writer.flush();
            } else {
                Log.w(TAG, "Attempted to send response on closed socket: " + response);
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException while sending response: " + response, e);
            throw e; // Re-throw to be handled by the main loop/caller
        }
    }

    private void closeDataConnection() {
        Log.d(TAG, "Closing data connection (if open).");
        try {
            if (dataSocket != null && !dataSocket.isClosed()) {
                dataSocket.close();
            }
            if (dataServerSocket != null && !dataServerSocket.isClosed()) {
                dataServerSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing data connection: " + e.getMessage(), e);
        }
        dataSocket = null;
        dataServerSocket = null;
    }
}