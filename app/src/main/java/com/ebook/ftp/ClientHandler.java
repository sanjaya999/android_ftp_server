package com.ebook.ftp;

import android.util.Log;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;


public class ClientHandler implements Runnable {


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
        this.rootDir = new File(rootDir).getCanonicalPath();
        this.currentDir = this.rootDir;

        reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));

        Log.d(TAG, "ClientHandler created for " + clientSocket.getRemoteSocketAddress() + " with root: " + this.rootDir);
    }

    /**
     * The main execution loop for handling client commands.
     * This method reads commands from the client, processes them, and sends appropriate responses.
     * It handles various FTP commands such as USER, PASS, PWD, CWD, PASV, LIST, NLST, RETR,
     * STOR, QUIT, FEAT, TYPE, SYST, OPTS, SIZE, and NOOP.
     *
     * The loop continues until the client disconnects or sends a QUIT command.
     * It also manages user authentication state and ensures that data connections are
     * properly handled and closed.
     *
     * Exception handling is in place for I/O errors and socket issues, logging them
     * for debugging purposes.
     *
     * Finally, it ensures all resources (sockets, readers, writers) are closed
     * when the client session ends, either normally or due to an error.
     */
    @Override
    public void run() {
        try {
            sendResponse("220 Welcome to the Android FTP server");
            String line;
            String user = null;

            try {
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "CMD: " + line);
                    String command = "";
                    String argument = "";

                    StringTokenizer tokenizer = new StringTokenizer(line);
                    if (tokenizer.hasMoreTokens()) {
                        command = tokenizer.nextToken().toUpperCase();
                        if (tokenizer.hasMoreTokens()) {
                            // Instead of using tokenizer again, we take a substring from the original line
                            argument = line.substring(command.length()).trim();
                        }
                    } else {
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
                                isLoggedIn = false;
                            }
                            break;

                            //print working directory
                        case "PWD":
                        case "XPWD":
                            if (!checkLoggedIn()) break;
                            // This is done to show the user's path *relative to the root*, not the full system path
                            String displayPath = currentDir.replace(rootDir, "");
                            if (displayPath.isEmpty()) displayPath = "/";
                            sendResponse("257 \"" + displayPath + "\" is the current directory.");
                            break;

                            //change working directory
                        case "CWD":
                            if (!checkLoggedIn()) break;
                            changeWorkingDirectory(argument);
                            break;

                            //passiveMode
                        case "PASV":
                            if (!checkLoggedIn()) break;
                            enterPassiveMode();
                            break;

                            //list directory
                        case "LIST":
                        case "NLST": // Handle NLST too
                            if (!checkLoggedIn()) break;
                            sendDirectoryListing(argument);
                            break;

                            //retrieve
                        case "RETR":
                            if (!checkLoggedIn()) break;
                            sendFile(argument);
                            break;

                            //STORe //uplod file to ftp server
                        case "STOR":
                            if (!checkLoggedIn()) break;
                            receiveFile(argument);
                            break;

                            //Quit
                        case "QUIT":
                            sendResponse("221 Goodbye.");
                            controlSocket.close();
                            return; // Exit the run method

                        //features
                        case "FEAT":
                            sendResponse("211-Features:");
                            sendResponse(" UTF8");
                            sendResponse(" PASV");
                            sendResponse(" MLSD"); // Modern clients often prefer MLSD
                            sendResponse(" SIZE");
                            sendResponse("211 End");
                            break;

                        case "TYPE":
                            // I is image/binary
                            if (argument.equalsIgnoreCase("I")) {
                                sendResponse("200 Type set to I (Binary)");
                            } else if (argument.equalsIgnoreCase("A")) {//ASCII
                                sendResponse("200 Type set to A (ASCII)");
                            } else {
                                sendResponse("504 Type not supported.");
                            }
                            break;

                            //SYSTEM / ask about file system and os
                        case "SYST":
                            sendResponse("215 UNIX Type: L8");
                            break;

                            //OPTIONS
                        case "OPTS":
                            if (argument.toUpperCase().startsWith("UTF8 ON")) {
                                sendResponse("200 UTF8 set to on");
                            } else {
                                sendResponse("501 Option not understood");
                            }
                            break;

                            //file length
                        case "SIZE":
                            if (!checkLoggedIn()) break;
                            handleSize(argument);
                            break;

                            //No operations
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

    /**
     * Builds a File object from a given path, ensuring it stays within the defined root directory.
     *
     * <p>This method handles both absolute and relative paths:
     * <ul>
     *   <li>Absolute paths (starting with "/") are resolved relative to the {@code rootDir}.
     *   <li>Relative paths are resolved relative to the {@code currentDir}.
     * </ul>
     *
     * <p>Crucially, it performs a security check to prevent path traversal attacks.
     * If the resolved canonical path of the target file falls outside the {@code rootDir},
     * an {@link IOException} is thrown.
     *
     * @param path The path string to build the File from. Can be absolute or relative.
     * @return A {@link File} object representing the resolved path.
     * @throws IOException If an I/O error occurs during path resolution or if the
     *                     resolved path is outside the allowed root directory.
     *                     This includes cases where the path is invalid or attempts
     *                     to access files outside the intended scope.
     */
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


        if (!canonicalPath.startsWith(rootDir) && !canonicalPath.equals(rootDir)) {
            Log.w(TAG, "Security Alert: Attempted access outside root: " + canonicalPath + " (Original: " + path + ")");
            throw new IOException("Access denied - Path outside root directory.");
        }

        return targetFile;
    }


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

                byte[] buffer = new byte[8192];
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


    /**
     * Sends a file to the FTP client in response to the RETR command.
     *
     * This method handles file transmission in passive mode. It verifies file validity,
     * opens the data connection, streams the file's binary content to the client,
     * and ensures proper FTP status codes are sent during each step of the process.
     *
     * @param filename Name or relative path of the file requested by the client.
     * @throws IOException If any file I/O or socket error occurs during the transfer.
     */
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

                byte[] buffer = new byte[8192];  // Use 8KB buffer to optimize read/write performance
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

    /**
     * Sends a directory listing to the client over the data connection.
     * <p>
     * This method is invoked in response to an FTP LIST command. It establishes a data connection,
     * lists the contents of the current directory, formats the information for each file/directory,
     * and sends it to the client. Appropriate FTP responses are sent for various stages
     * (e.g., connection opening, transfer completion, errors).
     *
     * @param argument The argument provided with the LIST command (currently unused).
     * @throws IOException If an I/O error occurs on the control connection.
     */
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
            sdf.setTimeZone(TimeZone.getDefault());

            if (files != null) {
                Log.d(TAG, "Listing directory: " + dir.getAbsolutePath() + " (" + files.length + " items)");
                for (File f : files) {
                    if (!f.canRead()) continue;

                    //permission string
                    String perms = (f.isDirectory() ? "d" : "-") +
                            (f.canRead() ? "r" : "-") +
                            (f.canWrite() ? "w" : "-") +
                            (f.canExecute() ? "x" : "-") +
                            "------";
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


    /**
     * Server opens port for client to connect.
     * Enters passive mode for data transfer.
     * <p>
     * This method closes any existing data connection, creates a new server socket on an
     * available port, and sends a 227 response to the client with the server's IP address
     * and the port number for the data connection.
     * <p>
     * The IP address and port are formatted as specified by the FTP protocol for the PASV command.
     * If a valid IPv4 address cannot be obtained, a 425 error response is sent, and the
     * data connection is closed.
     *
     * @throws IOException if an I/O error occurs when creating the server socket or sending the response.
     */
    private void enterPassiveMode() throws IOException {
        closeDataConnection();

        dataServerSocket = new ServerSocket(0);
        int port = dataServerSocket.getLocalPort();
        Log.d(TAG, "Passive mode started on port: " + port);

        String ip = controlSocket.getLocalAddress().getHostAddress();
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

    /**
     * Changes the current working directory for the FTP session.
     *
     * <p>This method handles the "CWD" (Change Working Directory) FTP command. It supports:
     * <ul>
     *     <li>Changing to the parent directory ("..").</li>
     *     <li>Changing to the root directory ("/" or "~").</li>
     *     <li>Changing to a specified subdirectory.</li>
     * </ul>
     * </p>
     * <p>
     * The method performs several checks:
     * <ul>
     *     <li>Ensures that navigation upwards ("..") does not go beyond the configured root directory.</li>
     *     <li>Verifies that the target directory exists, is a directory, and is readable.</li>
     * </ul>
     * Appropriate FTP response codes (250 for success, 550 for failure) are sent back to the client.
     * </p>
     *
     * @param dir The directory path to change to. This can be a relative or absolute path (relative to the current FTP session's root).
     *            Special values like "..", "/", or "~" are also handled.
     * @throws IOException If an I/O error occurs while interacting with the file system or sending the response.
     */
    private void changeWorkingDirectory(String dir) throws IOException {
        File newDir;

        if (dir.equals("..")) {
            File current = new File(currentDir);
            File parent = current.getParentFile();
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
            throw e;
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