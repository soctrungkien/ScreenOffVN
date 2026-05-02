package com.tile.screenoff;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleTcpServer {

    public interface TcpConnectionListener {
        void onReceive(byte[] data, Socket socket);
    }
    
    private ServerSocket serverSocket;
    private static final int CAPACITY = 1024 * 1024;
    private final TcpConnectionListener listener;
    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private boolean isRunning = false;

    public SimpleTcpServer(TcpConnectionListener listener, int port) {
        this.listener = listener;
        this.port = port;
    }

    public void start() {
        if (isRunning) return;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            isRunning = true;
            
            new Thread(() -> {
                while (isRunning) {
                    try {
                        Socket socket = serverSocket.accept();
                        executor.execute(() -> handleClient(socket));
                    } catch (IOException e) {
                        if (isRunning) e.printStackTrace();
                    }
                }
            }).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedInputStream in = new BufferedInputStream(socket.getInputStream())) {
            byte[] buf = new byte[CAPACITY];
            int size = in.read(buf);
            if (size > 0) {
                byte[] chunk = Arrays.copyOfRange(buf, 0, size);
                listener.onReceive(chunk, socket);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void sendResponse(Socket socket, byte[] data) {
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(data);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
            executor.shutdownNow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
