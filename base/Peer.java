package ds.assignment.tring;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import ds.assignment.tring.PoissonProcess;

public class Peer {
    String host;
    int port;
    String nextPeerHost;
    int nextPeerPort;
    String serverHost;
    int serverPort;
    int ringPosition;
    Random random;
    Logger logger;
    private PoissonProcess poissonProcess;
    BlockingQueue<String> requestQueue;
    
    public Peer(String hostname, int port, String nextPeerHost, int nextPeerPort, 
                String serverHost, int serverPort, int ringPosition) {
        this.host = hostname;
        this.port = port;
        this.nextPeerHost = nextPeerHost;
        this.nextPeerPort = nextPeerPort;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.ringPosition = ringPosition;
        this.random = new Random();
        this.requestQueue = new LinkedBlockingQueue<>();
        this.poissonProcess = new PoissonProcess(4.0, random); // Lambda = 4 per minute
        
        logger = Logger.getLogger("logfile");
        
        try {
            FileHandler handler = new FileHandler("./" + hostname + "_" + port + "_peer.log", true);
            logger.addHandler(handler);
            SimpleFormatter formatter = new SimpleFormatter();    
            handler.setFormatter(formatter);    
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void startRequestGenerator() {
        new Thread(() -> {
            logger.info("Starting request generator (Poisson λ=4 per minute)");
            
            while (true) {
                try {
                    double deltaMinutes = poissonProcess.timeForNextEvent();
                    long deltaMillis = (long)(deltaMinutes * 60000);
                    
                    Thread.sleep(deltaMillis);
                    
                    String[] operations = {"add", "sub", "mul", "div"};
                    String op = operations[random.nextInt(4)];
                    double x = random.nextDouble() * 100;
                    double y = (op.equals("div")) ? (random.nextDouble() * 100 + 1) : (random.nextDouble() * 100);
                    
                    String request = op + ":" + x + ":" + y;
                    requestQueue.offer(request);
                    
                    logger.info("GENERATED REQUEST: " + request + " (queue size: " + requestQueue.size() + ")");
                    System.out.println("[Peer " + ringPosition + "] Generated request: " + request);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "RequestGenerator-Peer" + ringPosition).start();
    }
    
    public void processAllRequests() {
        if (requestQueue.isEmpty()) {
            logger.info("No requests in queue - forwarding token immediately");
            return;
        }
        
        int queueSize = requestQueue.size();
        logger.info("HOLDING TOKEN - Processing " + queueSize + " requests");
        System.out.println("\n[Peer " + ringPosition + "] === HOLDING TOKEN - Processing " + queueSize + " requests ===");
        
        while (!requestQueue.isEmpty()) {
            String request = requestQueue.poll();
            
            try {
                Socket socket = new Socket(InetAddress.getByName(serverHost), serverPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                logger.info("SENDING TO SERVER: " + request);
                out.println(request);
                
                String result = in.readLine();
                logger.info("RECEIVED FROM SERVER: " + result);
                
                // Print to terminal as required by assignment
                System.out.println("[Peer " + ringPosition + "] Request: " + request + " => Result: " + result);
                
                socket.close();
                
            } catch (Exception e) {
                logger.warning("Failed to process request '" + request + "': " + e.getMessage());
                System.err.println("[Peer " + ringPosition + "] ERROR: " + e.getMessage());
            }
        }
        
        System.out.println("[Peer " + ringPosition + "] === FINISHED - Releasing token ===\n");
        logger.info("FINISHED processing all requests - releasing token");
    }
    
    public void forwardToken(String token) {
        try {
            Thread.sleep(20000);
            
            int currentRound = 0;
            if (token.contains("ROUND:")) {
                String[] parts = token.split(":");
                currentRound = Integer.parseInt(parts[2]);  // FIXED: Round is at index 2
            }
            
            if (ringPosition == 0) {
                currentRound++;
                logger.info("STARTING NEW ROUND: " + currentRound);
                System.out.println("[Peer " + ringPosition + "] Starting round " + currentRound);
            }
            
            // FIXED: Consistent format = TOKEN:ROUND:roundNumber:senderPosition
            token = "TOKEN:ROUND:" + currentRound + ":" + ringPosition;
            
            logger.info("FORWARDING TOKEN to " + nextPeerHost + ":" + nextPeerPort);
            
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    Socket socket = new Socket(InetAddress.getByName(nextPeerHost), nextPeerPort);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    out.println(token);
                    socket.close();
                    logger.info("Token forwarded successfully");
                    return;
                } catch (Exception e) {
                    logger.warning("Attempt " + attempt + " failed: " + e.getMessage());
                    if (attempt < maxRetries) Thread.sleep(5000);
                }
            }
            logger.severe("Failed to forward token after " + maxRetries + " attempts - TOKEN LOST");
            System.err.println("[Peer " + ringPosition + "] ERROR: TOKEN LOST!");
        } catch (Exception e) {
            logger.severe("Error in forwardToken: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.out.println("Usage: java Peer <host> <port> <next-peer-host> <next-peer-port> <server-host> <server-port> <ring-position>");
            System.out.println("Example: java Peer localhost 8081 localhost 8082 localhost 9000 0");
            return;
        }
        
        Peer peer = new Peer(
            args[0],                    // this host
            Integer.parseInt(args[1]),  // this port
            args[2],                    // next peer host
            Integer.parseInt(args[3]),  // next peer port
            args[4],                    // server host
            Integer.parseInt(args[5]),  // server port (FIXED: parsed to int)
            Integer.parseInt(args[6])   // ring position
        );
                            
        System.out.printf("=== Peer %d Configuration ===\n", peer.ringPosition);
        System.out.printf("This peer: %s:%d\n", peer.host, peer.port);
        System.out.printf("Next peer: %s:%d\n", peer.nextPeerHost, peer.nextPeerPort);
        System.out.printf("Server: %s:%d\n", peer.serverHost, peer.serverPort);
        System.out.println("============================\n");
        
        // Start token server
        new Thread(new TokenServer(args[0], Integer.parseInt(args[1]), peer, peer.logger)).start();
        
        // Start request generator
        Thread.sleep(1000); // Wait for server to start
        peer.startRequestGenerator();
    }
}


class TokenServer implements Runnable {
    String host;
    int port;
    ServerSocket server;
    Peer peer;
    Logger logger;
    
    public TokenServer(String host, int port, Peer peer, Logger logger) throws Exception {
        this.host = host;
        this.port = port;
        this.peer = peer;
        this.logger = logger;
        this.server = new ServerSocket(port, 1, InetAddress.getByName(host));
    }

    @Override
    public void run() {
        try {
            logger.info("Token server started on " + host + ":" + port);
            System.out.println("[Peer " + peer.ringPosition + "] Token server listening on " + host + ":" + port);
            while (true) {
                Socket client = server.accept();
                String clientAddress = client.getInetAddress().getHostAddress();
                new Thread(new TokenConnection(clientAddress, client, peer, logger)).start();
            }
        } catch (Exception e) {
            logger.severe("Token server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}


class TokenConnection implements Runnable {
    String clientAddress;
    Socket clientSocket;
    Peer peer;
    Logger logger;

    public TokenConnection(String clientAddress, Socket clientSocket, Peer peer, Logger logger) {
        this.clientAddress = clientAddress;
        this.clientSocket = clientSocket;
        this.peer = peer;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));    
            
            String token = in.readLine();
            logger.info("RECEIVED TOKEN from " + clientAddress + ": '" + token + "'");
            System.out.println("[Peer " + peer.ringPosition + "] Received token from " + clientAddress);

            if (token.contains("ROUND:")) {
                String[] parts = token.split(":");
                // FIXED: Format is TOKEN:ROUND:roundNumber:senderPosition
                if (parts.length >= 4) {
                    int round = Integer.parseInt(parts[2]);
                    int senderPosition = Integer.parseInt(parts[3]);
                    logger.info("Token is on round " + round + " (sent by peer " + senderPosition + ")");
                    System.out.println("[Peer " + peer.ringPosition + "] Token on round " + round + " from peer " + senderPosition);
                } else {
                    // Handle old format or initial token
                    logger.info("Received token: " + token);
                }
            }
            
            clientSocket.close();
            
            // Check queue and process all requests if not empty
            peer.processAllRequests();
            
            // Forward token to next peer
            new Thread(() -> peer.forwardToken(token)).start();
            
        } catch(Exception e) {
            logger.severe("Token connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}