package peertoconnect.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import peertoconnect.utils.PortUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileSharer {

    private static final Logger logger = Logger.getLogger(FileSharer.class.getName());

    private final Cache<Integer, String> availableFiles;
    private final ConcurrentMap<Integer, Boolean> activeDownloads;

    public FileSharer() {
        activeDownloads = new ConcurrentHashMap<>();
        this.availableFiles = CacheBuilder.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .removalListener((RemovalListener<Integer, String>) notification -> {
                    Integer port = notification.getKey();
                    String filePath = notification.getValue();

                    if (Boolean.TRUE.equals(activeDownloads.get(port))) {
                        logger.info("[SKIPPED TTL] Port " + port + " is still downloading.");
                        return;
                    }

                    File file = new File(filePath);
                    if (file.exists()) {
                        boolean deleted = file.delete();
                        logger.info("[TTL CLEANUP] File deleted (" + deleted + "): " + filePath);
                    } else {
                        logger.warning("[TTL CLEANUP] File not found to delete: " + filePath);
                    }
                }).build();
    }

    public int offerFile(String fileName) {
        int MAX_ATTEMPTS = 1000;
        int port;

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            port = PortUtils.getPort();
            if (availableFiles.getIfPresent(port) == null) {
                availableFiles.put(port, fileName);
                return port;
            }
        }
        throw new RuntimeException("Could not find an available port");
    }

    public void startFileServer(int port) {
        String filePath = availableFiles.getIfPresent(port);

        if (filePath == null) {
            logger.warning("No file associated with the given port: " + port);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Serving file: " + filePath + " on port: " + port);
            Socket clientSocket = serverSocket.accept();
            logger.info("Client connected from: " + clientSocket.getInetAddress());
            activeDownloads.put(port, true);
            new Thread(new FileSenderHandler(clientSocket, filePath, port, activeDownloads)).start();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Error starting file server on port " + port + ": " + ex.getMessage(), ex);
        }
    }

    private static class FileSenderHandler implements Runnable {

        private final Socket clientSocket;
        private final String filePath;
        private final int port;
        private final ConcurrentMap<Integer, Boolean> activeDownloads;

        public FileSenderHandler(Socket clientSocket, String filePath, int port, ConcurrentMap<Integer, Boolean> activeDownloads) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
            this.port = port;
            this.activeDownloads = activeDownloads;
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            File file = new File(filePath);
            long fileSize = file.length();

            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream oss = clientSocket.getOutputStream()) {

                String filename = file.getName();
                String header = "Filename: " + filename + "\n";
                oss.write(header.getBytes());

                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    oss.write(buffer, 0, bytesRead);
                }
                oss.flush();

                long end = System.currentTimeMillis();
                logger.info("File sent successfully: " + filename +
                        " | Size: " + fileSize + " bytes" +
                        " | Port: " + port +
                        " | Time: " + (end - start) + " ms");

            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error sending file on port " + port + ": " + e.getMessage(), e);
            } finally {
                activeDownloads.remove(port);
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Error closing client socket on port " + port + ": " + e.getMessage(), e);
                }
            }
        }
    }
}
