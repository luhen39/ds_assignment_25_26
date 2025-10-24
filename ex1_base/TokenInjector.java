package ds.assignment.tring;

import java.io.*;
import java.net.*;

public class TokenInjector {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java TokenInjector <host> <port>");
            return;
        }
        
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            System.out.println("Injecting token to " + host + ":" + port);
            out.println("TOKEN:INITIAL");
            System.out.println("Token injected successfully!");
            
        } catch (IOException e) {
            System.err.println("Failed to inject token: " + e.getMessage());
        }
    }
}