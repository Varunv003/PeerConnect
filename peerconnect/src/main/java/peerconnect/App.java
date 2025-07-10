package peerconnect;

import peerconnect.controller.FileController;

public class App {
    public static void main(String[] args) {
        try {
            FileController fileController = new FileController(8080);
            fileController.start();

            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    System.out.println("Shutting down the server");
                    fileController.stop();
                })
            );

            // Keeping the server running until the JVM is stopped (when docker stop)
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                fileController.stop();
            }

        } catch (Exception ex) {
            System.err.println("unable to start the server: " + ex.getMessage());
        }
    }
}
