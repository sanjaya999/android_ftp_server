package com.ebook.ftp;

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
import java.util.StringTokenizer;

public class ClientHandler implements Runnable{

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private final String username;
    private final String password;

    private  boolean isLoggedIn = false;
    private String rootDir;
    private String currentDir;

    private ServerSocket dataServerSocket;
    private  Socket dataSocket;

    public ClientHandler(Socket clientSocket, String username, String password , String rootDir) throws IOException {
        this.controlSocket = clientSocket;
        this.username = username;
        this.password = password;
        this.rootDir = rootDir;
        this.currentDir = rootDir;

        reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));

    }


    @Override
    public void run() {
        try {
            sendResponse("220 Welcome to the FTP server");
            String line;
            String user = null;

            try{
                while(true){
                    line = reader.readLine();
                    System.out.println("cmd: " + line);
                    String command = "";
                    String argument = "";
                    if (line == null){
                        System.out.println("client connected");
                        break;
                    }

                    StringTokenizer tokenizer = new StringTokenizer(line);
                    if(tokenizer.hasMoreTokens()){
                        command = tokenizer.nextToken().toUpperCase();
                        if(tokenizer.hasMoreTokens()) {
                            argument = line.substring(command.length()).trim();
                        }
                    }

                    switch (command){
                        case "USER":
                            user = argument;
                            sendResponse("331 User name ok, need password");
                            break;
                        case "PASS":
                            if (user != null && user.equals(username) && argument.equals(password)) {
                                isLoggedIn = true;
                                sendResponse("230 User logged in, proceed");
                            } else {
                                sendResponse("530 Login incorrect");
                            }
                            break;

                        case "PWD":
                            if (!checkLoggedIn()) break;
                            String displayPath = currentDir.replace(rootDir, "");
                            if (displayPath.isEmpty()) displayPath = "/";
                            sendResponse("257 \"" + displayPath + "\"");
                            break;

                        case "CWD":
                            checkLoggedIn();
                            changeWorkingDirectory(argument);
                            break;

                        case "PASV":
                            checkLoggedIn();
                            enterPassiveMode();
                            break;

                        case "LIST":
                            checkLoggedIn();
                            sendDirectoryListing();
                            break;

                        case "RETR":
                            checkLoggedIn();
                            sendFile(argument);
                            break;

                        case "STOR":
                            checkLoggedIn();
                            receiveFile(argument);
                            break;

                        case "QUIT":
                            sendResponse("221 Goodbye");
                            controlSocket.close();
                            return;
                        case "FEAT":
                            sendResponse("211-Features:");
                            sendResponse(" UTF8");
                            sendResponse("211 End");
                            break;
                        case "TYPE":
                            // Handle TYPE command (usually "TYPE I" for binary or "TYPE A" for ASCII)
                            sendResponse("200 Type set to " + argument);
                            break;

                        case "SYST":
                            sendResponse("215 UNIX Type: L8");
                            break;
                        case "OPTS":
                            if (argument.toUpperCase().startsWith("UTF8")) {
                                sendResponse("200 UTF8 set to on");
                            } else {
                                sendResponse("501 Option not understood");
                            }
                            break;

                        case "SITE":
                            sendResponse("214-The following SITE commands are recognized:");
                            sendResponse("214 End");
                            break;

                        case "NOOP":
                            sendResponse("200 NOOP command successful");
                            break;


                        default:
                            sendResponse("502 Command not implemented");
                            break;

                    }
                }

            }catch (SocketException e){
                System.out.println("client connection aborted" + e.getMessage());
            }

        } catch (IOException e) {
            System.err.println("IOException in ClientHandler: " + e.getMessage());
            e.printStackTrace();
        }
        finally {
            try{
                if (controlSocket !=null && !controlSocket.isClosed())controlSocket.close();
                if(dataServerSocket != null && !dataServerSocket.isClosed())dataServerSocket.close();
                if (dataSocket != null && !dataSocket.isClosed()) dataSocket.close();


            } catch (IOException ignored) {
                closeDataConnection();
            }
        }


    }

    private void receiveFile(String filename) throws IOException {
        File file = new File(currentDir , filename);

        dataSocket = dataServerSocket.accept();
        sendResponse("150 Opening ASCII mode data connection for file " + filename);

        BufferedInputStream in = new BufferedInputStream(dataSocket.getInputStream());
        FileOutputStream fos = new FileOutputStream(file);

        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            fos.write(buffer, 0, read);
        }
        fos.flush();
        fos.close();
        sendResponse("226 Transfer Completed");
    }

    private void sendFile(String filename) throws IOException {
        if (dataServerSocket == null) {
            sendResponse("425 Use PASV first");
            return;
        }

        File file = new File(currentDir , filename);
        if (!file.exists() || !file.isFile()) {
            sendResponse("550 File not found");
            return;
        }

        try {
            dataSocket = dataServerSocket.accept();
            sendResponse("150 Opening BINARY mode data connection for " + filename);

            try (BufferedOutputStream out = new BufferedOutputStream(dataSocket.getOutputStream());
                 FileInputStream fis = new FileInputStream(file)) {

                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            sendResponse("226 Transfer complete");
        }finally {
            closeDataConnection();
        }

    }

    private void sendDirectoryListing() throws IOException {
        dataSocket = dataServerSocket.accept();
        sendResponse("150 Opening ASCII mode data connection for file list");

        BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream()));
        File dir = new File(currentDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String line = (f.isDirectory() ? "d" : "-") + "rwxr-xr-x 1 user group " + f.length() + " Jan 1 00:00 " + f.getName() + "\r\n";
                dataWriter.write(line);
            }
        }
        dataWriter.flush();
        dataSocket.close();
        sendResponse("226 Transfer Completed");

    }


    private void enterPassiveMode() throws IOException {
        if (dataServerSocket != null && !dataServerSocket.isClosed()) {
            dataServerSocket.close();
        }
        dataServerSocket = new ServerSocket(0);
        int port = dataServerSocket.getLocalPort();

        String ip = controlSocket.getLocalAddress().getHostAddress();
        String[] ipParts = ip.split("\\.");

        int p1 = port/256;
        int p2 = port%256;

        sendResponse("227 Entering Passive Mode (" + ipParts[0] + "," + ipParts[1] + "," + ipParts[2] + "," + ipParts[3] + "," + p1 + "," + p2 + ")");

    }

    private void changeWorkingDirectory(String dir) throws IOException {
        if (dir.equals("..")) {
            // Handle parent directory
            File current = new File(currentDir);
            File parent = current.getParentFile();
            if (parent != null && parent.getAbsolutePath().startsWith(rootDir)) {
                currentDir = parent.getAbsolutePath();
                sendResponse("250 Directory successfully changed");
            } else {
                sendResponse("550 Cannot change to parent directory");
            }
        } else if (dir.equals("/") || dir.equals("~")) {
            // Handle root directory
            currentDir = rootDir;
            sendResponse("250 Directory successfully changed");
        } else {
            // Handle subdirectory
            File newDir = new File(currentDir, dir);
            if (newDir.exists() && newDir.isDirectory()) {
                currentDir = newDir.getCanonicalPath();
                sendResponse("250 Directory successfully changed");
            } else {
                sendResponse("550 Failed to change directory");
            }
        }
    }

    private boolean checkLoggedIn() throws IOException {
        if (!isLoggedIn) {
            sendResponse("530 Not logged in");
            return false;
        }
        return true;
    }
    private void sendResponse(String response) throws IOException {
        writer.write(response + "\r\n");
        writer.flush();

    }
    private void closeDataConnection() {
        try {
            if (dataSocket != null && !dataSocket.isClosed()) {
                dataSocket.close();
            }
            if (dataServerSocket != null && !dataServerSocket.isClosed()) {
                dataServerSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing data connection: " + e.getMessage());
        }
        dataSocket = null;
        dataServerSocket = null;
    }
}

