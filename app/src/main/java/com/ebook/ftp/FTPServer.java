package com.ebook.ftp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class FTPServer {



    private ServerSocket serverSocket;
    private boolean running = false;
    private int port;
    private ExecutorService threadPool;
    private final String username = "admin";
    private final String password = "admin";

    private String rootDir;

    public FTPServer(int port , String rootDir){
        this.threadPool = Executors.newCachedThreadPool();
        this.port = port;
        this.rootDir = rootDir;
    }

    public void Start() throws IOException{
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("ftp running on port " + port);

        while (running){
            try{
                Socket client = serverSocket.accept();
                System.out.println("client connected: " + client.getRemoteSocketAddress());
                threadPool.execute(new ClientHandler(client , username, password , rootDir));
            }catch (IOException e){
                System.err.println("Error accepting client connection: " + e.getMessage());

            }
        }
    }

    public void stop() throws IOException {
        running = false;

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // ✅ This will unblock `accept()`
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow(); // ✅ Don't call shutdown() on null
        }

        serverSocket.close();
        System.out.println("FTP server stopped");
    }


    public boolean isRunning() {
    return  running;
    }

}
