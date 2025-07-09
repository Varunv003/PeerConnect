package peertoconnect;

import peertoconnect.controller.FileController;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class App {
    public static void main(String[] args) {


        try{
            FileController fileController = new FileController(8080);
            fileController.start();

            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        System.out.println("Shutting down the server");
                        fileController.stop();
                    })
            );

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Press Enter to stop the server...");
            reader.readLine();  // Wait for Enter
            fileController.stop();


        }catch (Exception ex){
            System.err.println("unable to start the server" + ex.getMessage());

        }
    }
}
