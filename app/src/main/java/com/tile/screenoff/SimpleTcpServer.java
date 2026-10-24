package com.tile.screenoff;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class SimpleTcpServer {

    private static final String TAG = "ScreenOffHttp";
    private static final int MAX_HEADER_SIZE = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    public interface TcpConnectionListener {
        void onReceive(byte[] data, Socket socket);
    }

    private final TcpConnectionListener listener;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private volatile ExecutorService executor;
    private volatile Thread acceptThread;

    public SimpleTcpServer(TcpConnectionListener listener, int port) {
        this.listener = listener;
        this.port = port;
    }

    public synchronized boolean start() {
        if (isRunning) return true;
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            serverSocket = socket;
            executor = Executors.newCachedThreadPool();
            isRunning = true;
            acceptThread = new Thread(this::acceptLoop, "ScreenOffHttpAccept");
            acceptThread.start();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not bind port " + port + ": " + e);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
            closeServerSocket();
            return false;
        }
    }

    private void acceptLoop() {
        ServerSocket socket = serverSocket;
        Thread current = Thread.currentThread();
        while (isRunning && socket != null && acceptThread == current) {
            try {
                Socket client = socket.accept();
                ExecutorService currentExecutor = executor;
                if (currentExecutor == null || !isRunning) {
                    closeQuietly(client);
                    break;
                }
                try {
                    currentExecutor.execute(() -> handleClient(client));
                } catch (RejectedExecutionException e) {
                    closeQuietly(client);
                }
            } catch (IOException e) {
                if (isRunning && acceptThread == current) {
                    Log.e(TAG, "Accept failed: " + e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int headerEnd = -1;

            while (header.size() < MAX_HEADER_SIZE) {
                int remaining = MAX_HEADER_SIZE - header.size();
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                header.write(buffer, 0, read);
                headerEnd = findHeaderEnd(header.toByteArray());
                if (headerEnd >= 0) break;
            }

            if (headerEnd < 0) {
                Log.w(TAG, "Incomplete HTTP request");
                return;
            }

            byte[] request = Arrays.copyOf(header.toByteArray(), headerEnd);
            listener.onReceive(request, socket);
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "Client timed out");
        } catch (IOException e) {
            if (isRunning) Log.e(TAG, "Client read failed: " + e);
        } catch (Exception e) {
            Log.e(TAG, "Client handling failed: " + e);
        } finally {
            closeQuietly(socket);
        }
    }

    private static int findHeaderEnd(byte[] data) {
        for (int i = 0; i + 1 < data.length; i++) {
            if (i + 3 < data.length
                    && data[i] == '\r' && data[i + 1] == '\n'
                    && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return i + 2;
            }
        }
        return -1;
    }

    public void sendResponse(Socket socket, byte[] data) {
        if (socket == null || data == null) return;
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(data);
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "Response write failed: " + e);
        }
    }

    public synchronized void stop() {
        isRunning = false;
        closeServerSocket();

        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) currentExecutor.shutdownNow();

        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
