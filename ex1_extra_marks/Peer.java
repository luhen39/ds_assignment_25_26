package ds.assignment.tring;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ds.assignment.tring.PoissonProcess;

public class Peer {
    String host;
    int port;
    String nextPeerHost;
    int nextPeerPort;
    String serverHost;
    int serverPort;
    int ringPosition;
    int totalPeers;
    Random random;
    Logger logger;
    private PoissonProcess poissonProcess;
    BlockingQueue<String> requestQueue;
    
    // EXTRA MARKS: Token recovery fields
    AtomicLong lastTokenSeenTime;
    private static final long TOKEN_TIMEOUT_MS = 30000; // 30 seconds
    private static final long ACK_TIMEOUT_MS = 3000;    // 3 seconds
    volatile boolean hasToken = false;
    volatile boolean tokenMonitorActive = false;
    
    // EXTRA MARKS: Ring configuration (loaded from file)
    // "everyone maintains the current ring configuration" - from textbook
    private Map<Integer, PeerInfo> ringConfiguration;
    
    // EXTRA MARKS: Peer information structure
    static class PeerInfo {
        String host;
        int port;
        boolean alive;
        
        PeerInfo(String host, int port) {
            this.host = host;
            this.port = port;
            this.alive = true;
        }
    }
    
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
        this.poissonProcess = new PoissonProcess(4.0, random);
        this.lastTokenSeenTime = new AtomicLong(System.currentTimeMillis());
        this.ringConfiguration = new ConcurrentHashMap<>();
        
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
    
    // EXTRA MARKS: Load ring configuration from file
    // "everyone maintains the current ring configuration" - textbook requirement
    public void loadRingConfiguration(String configFile) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            int peerCount = 0;
            
            logger.info("Loading ring configuration from " + configFile);
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split(":");
                if (parts.length == 3) {
                    int position = Integer.parseInt(parts[0]);
                    String host = parts[1];
                    int port = Integer.parseInt(parts[2]);
                    
                    ringConfiguration.put(position, new PeerInfo(host, port));
                    peerCount++;
                    
                    logger.info("Loaded peer " + position + ": " + host + ":" + port);
                }
            }
            reader.close();
            
            this.totalPeers = peerCount;
            logger.info("Ring configuration loaded: " + totalPeers + " peers");
            System.out.println("[Peer " + ringPosition + "] Loaded ring with " + totalPeers + " peers");
            
        } catch (Exception e) {
            logger.severe("Failed to load ring configuration: " + e.getMessage());
            e.printStackTrace();
            System.err.println("ERROR: Could not load peers.config file!");
        }
    }
    
    // EXTRA MARKS: Start token timeout monitor
    public void startTokenMonitor() {
        new Thread(() -> {
            logger.info("Token monitor ready (will activate after first token)");
            System.out.println("[Peer " + ringPosition + "] Token monitor ready (waiting for first token)");
            
            while (true) {
                try {
                    Thread.sleep(2000); // Check every 5 seconds
                    
                    // ONLY CHECK IF MONITOR IS ACTIVE
                    if (!tokenMonitorActive) {
                        continue; // Skip check until first token received
                    }
                    
                    long timeSinceLastToken = System.currentTimeMillis() - lastTokenSeenTime.get();
                    
                    if (timeSinceLastToken > TOKEN_TIMEOUT_MS && !hasToken) {
                        logger.severe("TOKEN TIMEOUT! Last seen " + (timeSinceLastToken/1000) + "s ago");
                        System.err.println("[Peer " + ringPosition + "] *** TOKEN LOST - Initiating recovery ***");
                        
                        // Simple election: lowest alive peer position regenerates
                        if (shouldRegenerateToken()) {
                            regenerateToken();
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "TokenMonitor-Peer" + ringPosition).start();
    }
    
    // EXTRA MARKS: Simple election - lowest alive peer regenerates token
    private boolean shouldRegenerateToken() {
        for (int i = 0; i < totalPeers; i++) {
            PeerInfo peer = ringConfiguration.get(i);
            if (peer != null && peer.alive) {
                if (i == ringPosition) {
                    logger.info("This peer (position " + ringPosition + ") will regenerate token");
                    return true;
                } else {
                    logger.info("Peer " + i + " should regenerate token (lower position)");
                    return false;
                }
            }
        }
        // Fallback if no info available
        return ringPosition == 0;
    }
    
    // EXTRA MARKS: Regenerate lost token
    private void regenerateToken() {
        logger.severe("REGENERATING TOKEN at peer " + ringPosition);
        System.err.println("[Peer " + ringPosition + "] *** CREATING NEW TOKEN ***");
        
        String newToken = "TOKEN:ROUND:1:" + ringPosition + ":REGENERATED";
        hasToken = true;
        lastTokenSeenTime.set(System.currentTimeMillis());
        
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                processAllRequests();
                hasToken = false;
                forwardTokenWithAck(newToken);
            } catch (Exception e) {
                logger.severe("Error during token regeneration: " + e.getMessage());
            }
        }).start();
    }
    
    // EXTRA MARKS: Forward token with ACK mechanism
    public void forwardTokenWithAck(String token) {
        try {
            Thread.sleep(2000);
            
            int currentRound = 0;
            if (token.contains("ROUND:")) {
                String[] parts = token.split(":");
                currentRound = Integer.parseInt(parts[2]);
            }
            
            if (ringPosition == 0 && !token.contains("REGENERATED")) {
                currentRound++;
                logger.info("STARTING NEW ROUND: " + currentRound);
                System.out.println("[Peer " + ringPosition + "] Starting round " + currentRound);
            }
            
            token = "TOKEN:ROUND:" + currentRound + ":" + ringPosition;
            
            logger.info("FORWARDING TOKEN to " + nextPeerHost + ":" + nextPeerPort);
            
            // EXTRA MARKS: Try to forward with ACK
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    Socket socket = new Socket(InetAddress.getByName(nextPeerHost), nextPeerPort);
                    socket.setSoTimeout((int)ACK_TIMEOUT_MS);
                    
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    out.println(token);
                    logger.info("Sent token to " + nextPeerHost + ":" + nextPeerPort);
                    
                    // EXTRA MARKS: Wait for ACK
                    String ack = in.readLine();
                    
                if ("ACK".equals(ack)) {
                    logger.info("Received ACK - token forwarded successfully");
                    lastTokenSeenTime.set(System.currentTimeMillis());  // ADD THIS
                    socket.close();
                    return;
                    }
                    
                    socket.close();
                    
                } catch (java.net.SocketTimeoutException e) {
                    logger.warning("ACK timeout (attempt " + attempt + "/" + maxRetries + ")");
                    System.err.println("[Peer " + ringPosition + "] ACK timeout from next peer (attempt " + attempt + ")");
                    
                } catch (java.net.ConnectException e) {
                    logger.warning("Connection refused (attempt " + attempt + "/" + maxRetries + ")");
                    System.err.println("[Peer " + ringPosition + "] Next peer unreachable (attempt " + attempt + ")");
                    
                } catch (Exception e) {
                    logger.warning("Attempt " + attempt + " failed: " + e.getMessage());
                }
                
                if (attempt < maxRetries) {
                    Thread.sleep(2000);
                }
            }
            
            // EXTRA MARKS: Next peer is dead - skip it using ring configuration
            logger.severe("Next peer (" + nextPeerHost + ":" + nextPeerPort + ") appears DEAD");
            System.err.println("[Peer " + ringPosition + "] *** NEXT PEER DEAD - Attempting skip ***");
            
            skipDeadPeerAndForward(token);
            
        } catch (Exception e) {
            logger.severe("Error in forwardTokenWithAck: " + e.getMessage());
        }
    }
    
    // EXTRA MARKS: Skip dead peer using ring configuration
    // "throw the token over the head of the dead process to the next member down the line"
    private void skipDeadPeerAndForward(String token) {
        try {
            // Mark current next peer as dead
            int deadPeerPosition = (ringPosition + 1) % totalPeers;
            PeerInfo deadPeer = ringConfiguration.get(deadPeerPosition);
            if (deadPeer != null) {
                deadPeer.alive = false;
                logger.info("Marked peer " + deadPeerPosition + " as DEAD");
            }
            
            // Find next alive peer in the ring
            int nextAlivePosition = (deadPeerPosition + 1) % totalPeers;
            int attempts = 0;
            
            while (attempts < totalPeers) {
                PeerInfo nextPeer = ringConfiguration.get(nextAlivePosition);
                
                if (nextPeer != null && nextPeer.alive) {
                    logger.info("Skipping dead peer " + deadPeerPosition + ", forwarding to peer " + nextAlivePosition);
                    System.out.println("[Peer " + ringPosition + "] Skipping dead peer, trying peer " + nextAlivePosition);
                    
                    // Try to forward to this peer
                    Socket socket = new Socket(InetAddress.getByName(nextPeer.host), nextPeer.port);
                    socket.setSoTimeout((int)ACK_TIMEOUT_MS);
                    
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    out.println(token);
                    String ack = in.readLine();
                    
                    if ("ACK".equals(ack)) {
                        logger.info("Successfully skipped dead peer and forwarded to peer " + nextAlivePosition);
                        System.out.println("[Peer " + ringPosition + "] ✓ Successfully skipped dead peer");
                        
                        // EXTRA MARKS: Update next peer to skip the dead one permanently
                        this.nextPeerHost = nextPeer.host;
                        this.nextPeerPort = nextPeer.port;
                        logger.info("Updated next peer to: " + nextPeerHost + ":" + nextPeerPort);
                        
                        socket.close();
                        return;
                    }
                    
                    socket.close();
                    
                    // This peer also appears dead
                    nextPeer.alive = false;
                    logger.warning("Peer " + nextAlivePosition + " also appears dead");
                }
                
                nextAlivePosition = (nextAlivePosition + 1) % totalPeers;
                attempts++;
            }
            
            logger.severe("All peers appear dead - TOKEN LOST!");
            System.err.println("[Peer " + ringPosition + "] ERROR: All peers dead - TOKEN LOST");
            
        } catch (Exception e) {
            logger.severe("Failed to skip dead peer: " + e.getMessage());
            System.err.println("[Peer " + ringPosition + "] Failed to skip dead peer - TOKEN LOST");
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
    
    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            System.out.println("USAGE: java Peer <host> <port> <next-peer-host> <next-peer-port> <server-host> <server-port> <ring-position> <config-file>");
            System.out.println();
            System.out.println("EXAMPLE:");
            System.out.println("  java ds.assignment.tring.Peer localhost 8081 localhost 8082 localhost 9000 0 peers.config");
            System.out.println();
            System.out.println("BASIC REQUIREMENT COMPLIANCE:");
            System.out.println("  - Each peer only knows next peer IP (command line args 2-3)");
            System.out.println("  - All peers know server IP (command line args 4-5)");
            System.out.println();
            System.out.println("EXTRA MARKS:");
            System.out.println("  - Ring configuration loaded from file (arg 7)");
            System.out.println("  - Enables token regeneration and dead peer skipping");
            return;
        }
        
        Peer peer = new Peer(
            args[0],                    // this host
            Integer.parseInt(args[1]),  // this port
            args[2],                    // next peer host (BASIC REQUIREMENT)
            Integer.parseInt(args[3]),  // next peer port
            args[4],                    // server host (BASIC REQUIREMENT)
            Integer.parseInt(args[5]),  // server port
            Integer.parseInt(args[6])   // ring position
        );
        
        // EXTRA MARKS: Load ring configuration
        // "everyone maintains the current ring configuration" - textbook
        peer.loadRingConfiguration(args[7]);
                            
        System.out.println("====================================");
        System.out.printf("=== Peer %d/%d Configuration ===\n", peer.ringPosition, peer.totalPeers);
        System.out.println("====================================");
        System.out.printf("This peer:  %s:%d\n", peer.host, peer.port);
        System.out.printf("Next peer:  %s:%d (from command line)\n", peer.nextPeerHost, peer.nextPeerPort);
        System.out.printf("Server:     %s:%d\n", peer.serverHost, peer.serverPort);
        System.out.printf("Ring config: Loaded from %s\n", args[7]);
        System.out.println("====================================");
        System.out.println("EXTRA MARKS features enabled:");
        System.out.println("  ✓ Token timeout detection (30s)");
        System.out.println("  ✓ Token regeneration");
        System.out.println("  ✓ ACK mechanism (3s timeout)");
        System.out.println("  ✓ Dead peer detection");
        System.out.println("  ✓ Dead peer skipping");
        System.out.println("====================================\n");
        
        // Start token server
        new Thread(new TokenServer(args[0], Integer.parseInt(args[1]), peer, peer.logger)).start();
        
        Thread.sleep(1000);
        
        // Start request generator
        peer.startRequestGenerator();
        
        // EXTRA MARKS: Start token monitor
        peer.startTokenMonitor();
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
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            
            String token = in.readLine();
            
            // EXTRA MARKS: Update last seen time
            peer.lastTokenSeenTime.set(System.currentTimeMillis());
            peer.hasToken = true;
            
            // ACTIVATE MONITOR ON FIRST TOKEN
            if (!peer.tokenMonitorActive) {
                peer.tokenMonitorActive = true;
                logger.info("Token monitor ACTIVATED (first token received)");
                System.out.println("[Peer " + peer.ringPosition + "] ✓ Token monitor now active");
            }
            
            logger.info("RECEIVED TOKEN from " + clientAddress + ": '" + token + "'");
            System.out.println("[Peer " + peer.ringPosition + "] Received token");

            // EXTRA MARKS: Send ACK immediately
            out.println("ACK");
            out.flush();
            logger.info("Sent ACK to " + clientAddress);

            if (token.contains("ROUND:")) {
                String[] parts = token.split(":");
                if (parts.length >= 4) {
                    int round = Integer.parseInt(parts[2]);
                    int senderPosition = Integer.parseInt(parts[3]);
                    logger.info("Token is on round " + round + " (sent by peer " + senderPosition + ")");
                    
                    if (token.contains("REGENERATED")) {
                        System.out.println("[Peer " + peer.ringPosition + "] *** RECEIVED REGENERATED TOKEN ***");
                    }
                }
            }
            
            clientSocket.close();
            
            // Process all requests
            peer.processAllRequests();

            // UPDATE: Token still in circulation
            peer.lastTokenSeenTime.set(System.currentTimeMillis());  // ADD THIS
            
            // Forward token with ACK mechanism
            peer.hasToken = false;
            new Thread(() -> peer.forwardTokenWithAck(token)).start();
            
        } catch(Exception e) {
            logger.severe("Token connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}