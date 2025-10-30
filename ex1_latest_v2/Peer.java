package ds.assignment.tring;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    final String host;
    final int port;
    String nextPeerHost;
    int nextPeerPort;
    final String serverHost;
    final int serverPort;
    final int ringPosition;
    int totalPeers;
    Random random;
    Logger logger;
    private PoissonProcess poissonProcess;
    BlockingQueue<String> requestQueue;
    
    // Token recovery fields
    AtomicLong lastTokenSeenTime;
    private static final long TOKEN_TIMEOUT_MS = 90000; // 90 seconds (5 minutes for testing)
    private static final long ACK_TIMEOUT_MS = 3000;    // 3 seconds
    volatile boolean hasToken = false;
    volatile boolean tokenMonitorActive = false;
    volatile boolean tokenLost = false;
    boolean shouldMonitorTimeout = false;
    
    // Ring configuration (loaded from peers.config file)
    // "everyone maintains the current ring configuration", from textbook
    private Map<Integer, PeerInfo> ringConfiguration;
    
    // Peer information structure
    static class PeerInfo {
        final String host;
        final int port;
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
    
    public void startTokenMonitor() {
        new Thread(() -> {
            logger.info("Token monitor ready, activates after first token");
            System.out.println("[Peer " + ringPosition + "] Token monitor ready (waiting for first token)");
            
            while (true) {
                try {
                    Thread.sleep(2000); // Check every 2 seconds
                    
                    // Check only if monitor is active
                    if (!tokenMonitorActive || !shouldMonitorTimeout) {
                        continue; // Skip check until first token received
                    }
                    
                    long timeSinceLastToken = System.currentTimeMillis() - lastTokenSeenTime.get();
                    
                    if (timeSinceLastToken > TOKEN_TIMEOUT_MS && !hasToken) {
                        tokenLost = true;
                        logger.severe("Token timeout detected, last seen " + (timeSinceLastToken/1000) + "s ago");
                        System.err.println("[Peer " + ringPosition + "] Token lost, initiating recovery");
                        
                        // Simple election: lowest alive peer position regenerates
                        if (shouldRegenerateToken()) {
                            regenerateToken();
                            tokenLost = false;
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "TokenMonitor-Peer" + ringPosition).start();
    }
    
    private boolean shouldRegenerateToken() {
    int lowestAlivePosition = -1;
    
    // Find the lowest position peer that is actually alive
    for (int i = 0; i < totalPeers; i++) {
        PeerInfo peerInfo = ringConfiguration.get(i);
        
        if (peerInfo != null) {
            boolean actuallyAlive = false;
            
            if (i == ringPosition) {
                actuallyAlive = true;  // Current peer is alive
            } else {
                // Try to connect to verify
                try {
                    Socket testSocket = new Socket(peerInfo.host, peerInfo.port);
                    testSocket.setSoTimeout(2000);
                    testSocket.close();
                    actuallyAlive = true;
                } catch (IOException e) {
                    peerInfo.alive = false;
                    actuallyAlive = false;
                }
            }
            
            if (actuallyAlive && (lowestAlivePosition == -1 || i < lowestAlivePosition)) {
                lowestAlivePosition = i;
            }
        }
    }
    
    // Regenerate only if this peer is the lowest alive
    if (lowestAlivePosition == ringPosition) {
        logger.info("This peer (position " + ringPosition + ") will regenerate token");
        return true;
    }
    
    return false;
}
    
    private void regenerateToken() {
    logger.severe("Regenerating token at peer " + ringPosition);
    System.err.println("[Peer " + ringPosition + "] Creating new token");
    
    String newToken = "TOKEN:ROUND:1:" + ringPosition + ":REGENERATED";
    hasToken = true;
    lastTokenSeenTime.set(System.currentTimeMillis());
    
    // Process and forward synchronously
    try {
        Thread.sleep(1000);
        processAllRequests();
        hasToken = false;
        forwardTokenWithAck(newToken);
        
        logger.info("Token regeneration completed successfully");
        
    } catch (Exception e) {
        logger.severe("Error during token regeneration: " + e.getMessage());
        e.printStackTrace();
    }
}
    
    public void forwardTokenWithAck(String token) {
    try {
        // Check for peer recoveries before forwarding
        for (int i = 0; i < totalPeers; i++) {
            PeerInfo peer = ringConfiguration.get(i);
            
            if (peer != null && !peer.alive) {
                try {
                    Socket testSocket = new Socket(peer.host, peer.port);
                    testSocket.setSoTimeout(1000);
                    testSocket.close();
                    
                    // Peer is back
                    peer.alive = true;
                    
                    logger.info("Peer recovered: peer " + i + " is back online!");
                    System.out.println("[Peer " + ringPosition + "] Peer " + i + " recovered!");
                    
                } catch (IOException e) {
                    // Still dead, continue checking others
                }
            }
        }
        
        // Reset to original next peer if its alive now
        int originalNextPeer = (ringPosition + 1) % totalPeers;
        PeerInfo originalPeer = ringConfiguration.get(originalNextPeer);
        if (originalPeer != null && originalPeer.alive && 
            !(nextPeerHost.equals(originalPeer.host) && nextPeerPort == originalPeer.port)) {
            
            this.nextPeerHost = originalPeer.host;
            this.nextPeerPort = originalPeer.port;
            
            logger.info("Ring restored: next peer reset to peer " + originalNextPeer);
            System.out.println("[Peer " + ringPosition + "] Ring topology restored!");
        }

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
        
        final String finalToken = token;  // Create final copy for lambda

        // Check for self-forwarding
        if (nextPeerHost.equals(this.host) && nextPeerPort == this.port) {
            logger.info("No other alive peers, keeping token");
            System.out.println("[Peer " + ringPosition + "] No other alive peers, keeping token");

            this.hasToken = true;
            
            // Periodically check for peer recoveries
            new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(10000);  // Check every 10 seconds
                        
                        for (int i = 0; i < totalPeers; i++) {
                            if (i == ringPosition) continue;  // Skip self
                            
                            PeerInfo peer = ringConfiguration.get(i);
                            if (peer != null && !peer.alive) {
                                try {
                                    Socket testSocket = new Socket(peer.host, peer.port);
                                    testSocket.setSoTimeout(1000);
                                    testSocket.close();
                                    
                                    // Peer recovered
                                    peer.alive = true;
                                    logger.info("Peer recovered: peer " + i + " is back online!");
                                    System.out.println("[Peer " + ringPosition + "] Peer " + i + " recovered!");
                                    
                                    // Now try to forward the token to the recovered peer
                                    this.nextPeerHost = peer.host;
                                    this.nextPeerPort = peer.port;
                                    forwardTokenWithAck(finalToken);  // Use final copy
                                    return;
                                    
                                } catch (IOException e) {
                                    // Still dead
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "RecoveryChecker-Peer" + ringPosition).start();

            // Periodically process requests while keeping the token
            new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(5000);  // Process every 5 seconds
                        if (!requestQueue.isEmpty()) {
                            processAllRequests();
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "RequestProcessor-Peer" + ringPosition).start();
            
            return;
        }
        
        // Try to forward with ACK receipt
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Socket socket = new Socket(InetAddress.getByName(nextPeerHost), nextPeerPort);
                socket.setSoTimeout((int)ACK_TIMEOUT_MS);
                
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                out.println(token);
                logger.info("Sent token to " + nextPeerHost + ":" + nextPeerPort);
                
                // Wait for ACK receipt
                String ack = in.readLine();
                
                if ("ACK".equals(ack)) {
                    logger.info("Received ACK -> token forwarded successfully");
                    lastTokenSeenTime.set(System.currentTimeMillis()); // Update last seen time
                    socket.close();

                    // TEMPORARY: Sleep for testing (remove for production)
                    Thread.sleep(1000);

                    return;
                }
                
                socket.close();
                
            } catch (Exception e) {
                // Find peer position for logging
                int peerPosition = -1;
                for (Map.Entry<Integer, PeerInfo> entry : ringConfiguration.entrySet()) {
                    if (entry.getValue().host.equals(nextPeerHost) && entry.getValue().port == nextPeerPort) {
                        peerPosition = entry.getKey();
                        break;
                    }
                }
                if (peerPosition != -1) {
                    logger.warning("Peer " + peerPosition + " connection attempt " + attempt + "/" + maxRetries + " failed: " + e.getMessage());
                } else {
                    logger.warning(nextPeerHost + ":" + nextPeerPort + " connection attempt " + attempt + "/" + maxRetries + " failed: " + e.getMessage());
                }
                
                if (attempt < maxRetries) {
                    Thread.sleep(2000);
                }
            }
        }
        
        // Next peer is dead, so we skip it using ring configuration
        logger.severe("Next peer (" + nextPeerHost + ":" + nextPeerPort + ") appears dead");
        System.err.println("[Peer " + ringPosition + "] Next peer dead, attempting skip");
        
        skipDeadPeerAndForward(token);
        
    } catch (Exception e) {
        logger.severe("Error in forwardTokenWithAck: " + e.getMessage());
    }
}
    
    private void skipDeadPeerAndForward(String token) {
    try {
        // Mark current next peer as dead
        int deadPeerPosition = (ringPosition + 1) % totalPeers;
        PeerInfo deadPeer = ringConfiguration.get(deadPeerPosition);
        if (deadPeer != null) {
            deadPeer.alive = false;
            logger.info("Marked peer " + deadPeerPosition + " as dead");
        }
        
        // Find next alive peer in the ring
        int nextAlivePosition = (deadPeerPosition + 1) % totalPeers;
        int attempts = 0;
        
        while (attempts < totalPeers) {
            PeerInfo nextPeer = ringConfiguration.get(nextAlivePosition);
            
            if (nextPeer != null && nextPeer.alive) {
                logger.info("Skipping dead peer " + deadPeerPosition + ", forwarding to peer " + nextAlivePosition);
                System.out.println("[Peer " + ringPosition + "] Skipping dead peer, trying peer " + nextAlivePosition);
                
                // Try to forward to this peer with retry attempts (3)
                boolean forwardSuccessful = false;
                int maxRetries = 3;
                
                for (int retry = 1; retry <= maxRetries; retry++) {
                    try {
                        Socket socket = new Socket(InetAddress.getByName(nextPeer.host), nextPeer.port);
                        socket.setSoTimeout((int)ACK_TIMEOUT_MS);
                        
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        
                        out.println(token);
                        String ack = in.readLine();
                        
                        if ("ACK".equals(ack)) {
                        logger.info("Successfully skipped dead peer(s) and forwarded to peer " + nextAlivePosition);
                        
                        // Update next peer FIRST
                        this.nextPeerHost = nextPeer.host;
                        this.nextPeerPort = nextPeer.port;
                        logger.info("Updated next peer to: " + nextPeerHost + ":" + nextPeerPort);
                        
                        // TEMPORARY: Sleep for testing
                        Thread.sleep(1000);
                        System.out.println("[Peer " + ringPosition + "] Successfully skipped dead peer(s)");
                        
                        socket.close();
                        return; 
                    }
                        
                        socket.close();
                        
                    } catch (Exception e) {
                        logger.warning("Peer " + nextAlivePosition + " connection attempt " + retry + "/3 failed: " + e.getMessage());
                        
                        if (retry < maxRetries) {
                            Thread.sleep(2000);  // Wait 2 seconds before retry attempt
                        }
                    }
                }
                
                // All 3 retries failed, so this peer is also dead
                logger.warning("Peer " + nextAlivePosition + " also appears dead after 3 attempts");
                System.err.println("[Peer " + ringPosition + "] Peer " + nextAlivePosition + " also dead (3 retries failed), continuing...");
                nextPeer.alive = false;  // Mark this peer as dead after 3 attempts
                deadPeerPosition = nextAlivePosition;  // Update for log message
            }
            
            // Move to next peer in ring
            nextAlivePosition = (nextAlivePosition + 1) % totalPeers;
            attempts++;
        }
        
        // No alive peers found (except self), keep the token
        logger.info("No alive peers to forward to, keeping token");
        System.out.println("[Peer " + ringPosition + "] No alive peers to forward to, keeping token");
        
    } catch (Exception e) {
        logger.severe("Failed to skip dead peer: " + e.getMessage());
        System.err.println("[Peer " + ringPosition + "] Failed to skip dead peer -> Token Lost");
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
                    
                    logger.info("Generated request: " + request + " (queue size: " + requestQueue.size() + ")");
                    System.out.println("[Peer " + ringPosition + "] Generated request: " + request);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "RequestGenerator-Peer" + ringPosition).start();
    }
    
    public void processAllRequests() {
        if (requestQueue.isEmpty()) {
            logger.info("No requests in queue -> forwarding token immediately");
        // TEMPORARY: Sleep for testing (remove for production)
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // Ignore
        }
            return;
        }
        int queueSize = requestQueue.size();
        logger.info("Processing " + queueSize + " requests");
        
        while (!requestQueue.isEmpty()) {
            String request = requestQueue.poll();
            
            try {
                Socket socket = new Socket(InetAddress.getByName(serverHost), serverPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                logger.info("Sending to server: " + request);
                out.println(request);
                
                String result = in.readLine();
                logger.info("Received from server: " + result);
                
                System.out.println("[Peer " + ringPosition + "] Request: " + request + " => Result: " + result);
                
                // TEMPORARY: Sleep for testing (remove for production)
                Thread.sleep(500);

                socket.close();
                
            } catch (Exception e) {
                logger.warning("Failed to process request '" + request + "': " + e.getMessage());
                System.err.println("[Peer " + ringPosition + "] ERROR: " + e.getMessage());
            }
        }
        
        logger.info("Finished processing all requests");
    }
    
    public static void main(String[] args) throws Exception {
    if (args.length != 8) {
        System.out.println("Usage: java Peer <host> <port> <next-peer-host> <next-peer-port> <server-host> <server-port> <ring-position> <config-file>");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java ds.assignment.tring.Peer localhost 8081 localhost 8082 localhost 9000 0 peers.config");
        System.out.println();
        System.out.println("- Each peer only knows next peer IP (command line args 2-3)");
        System.out.println("- All peers know server IP (command line args 4-5)");
        System.out.println();
        System.out.println("- Ring configuration loaded from file (arg 7)");
        System.out.println("- Enables token regeneration and dead peer skipping");
        return;
    }
    
    Peer peer = new Peer(
        args[0],                    // this host
        Integer.parseInt(args[1]),  // this port
        args[2],                    // next peer host
        Integer.parseInt(args[3]),  // next peer port
        args[4],                    // server host 
        Integer.parseInt(args[5]),  // server port
        Integer.parseInt(args[6])   // ring position
    );
    
    // Load ring configuration
    peer.loadRingConfiguration(args[7]);

    // Start dedicated recovery thread
    new Thread(() -> {
        while (true) {
            try {
                Thread.sleep(5000);  // Check every 5 seconds
                
                boolean anyRecovery = false;
                
                for (int i = 0; i < peer.totalPeers; i++) {
                    if (i == peer.ringPosition) continue;  // Skip self
                    PeerInfo p = peer.ringConfiguration.get(i);
                    if (p != null && !p.alive) {
                        try {
                            Socket testSocket = new Socket(p.host, p.port);
                            testSocket.setSoTimeout(1000);
                            testSocket.close();
                            p.alive = true;
                            peer.logger.info("Peer recovered: peer " + i + " is back online!");
                            System.out.println("[Peer " + peer.ringPosition + "] Peer " + i + " recovered!");
                            anyRecovery = true;
                            
                        } catch (IOException e) {
                            // Still dead
                        }
                    }
                }
                
                // Update nextPeer to the next alive peer in ring order (always, not just on recovery)
                if (anyRecovery || peer.nextPeerHost.equals(peer.host)) {
                    int nextAlivePosition = (peer.ringPosition + 1) % peer.totalPeers;
                    boolean wasPointingToSelf = peer.nextPeerHost.equals(peer.host) && peer.nextPeerPort == peer.port;
                    
                    while (nextAlivePosition != peer.ringPosition) {
                        PeerInfo nextPeer = peer.ringConfiguration.get(nextAlivePosition);
                        if (nextPeer != null && nextPeer.alive) {
                            if (!peer.nextPeerHost.equals(nextPeer.host) || peer.nextPeerPort != nextPeer.port) {
                                peer.nextPeerHost = nextPeer.host;
                                peer.nextPeerPort = nextPeer.port;
                                peer.logger.info("Updated next peer to peer " + nextAlivePosition + " (" + peer.nextPeerHost + ":" + peer.nextPeerPort + ")");
                                
                                // If was keeping token (pointing to self), forward it now
                                if (wasPointingToSelf && peer.hasToken) {
                                    peer.logger.info("Triggering token forward after recovery while keeping token");
                                    new Thread(() -> {
                                        try {
                                            Thread.sleep(1000);
                                            peer.forwardTokenWithAck("TOKEN:ROUND:1:" + peer.ringPosition);
                                        } catch (Exception e) {
                                            peer.logger.severe("Error forwarding after recovery: " + e.getMessage());
                                        }
                                    }).start();
                                }
                            }
                            break;
                        }
                        nextAlivePosition = (nextAlivePosition + 1) % peer.totalPeers;
                    }
                }
                
            } catch (InterruptedException e) {
                break;
            }
        }
    }, "RecoveryChecker-Peer" + peer.ringPosition).start();
    
    // Start token server
    TokenServer tokenServer = peer.new TokenServer(args[0], Integer.parseInt(args[1]), peer, peer.logger);
    new Thread(tokenServer, "TokenServer-Peer" + peer.ringPosition).start();

    Thread.sleep(1000);

    // Start request generator
    peer.startRequestGenerator();
    
    // Start token monitor
    peer.startTokenMonitor();

    // Monitoring activation (immediate for all peers)
    peer.tokenMonitorActive = true;
    peer.shouldMonitorTimeout = true;
    peer.lastTokenSeenTime.set(System.currentTimeMillis());
    peer.logger.info("Monitoring activated immediately at startup");
    System.out.println("[Peer " + peer.ringPosition + "] Monitoring activated immediately");
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
        if (token == null) {
            // This is just a connection we can ignore
            clientSocket.close();
            return;
        }
        
        // Update last seen time
        peer.lastTokenSeenTime.set(System.currentTimeMillis());
        peer.hasToken = true;
        
        // Activate monitor on first token, for Peer 0 only
        if (!peer.tokenMonitorActive && peer.ringPosition == 0) {
            peer.tokenMonitorActive = true;
            peer.shouldMonitorTimeout = true;
            logger.info("Token monitor activated (Peer 0 received first token)");
            System.out.println("[Peer " + peer.ringPosition + "] Token monitor activated");
        } else if (peer.tokenMonitorActive) {
            // Already active, just update that this peer has seen the token
            peer.shouldMonitorTimeout = true;
        }
        
        logger.info("Received token from " + clientAddress + ": '" + token + "'");
        System.out.println("[Peer " + peer.ringPosition + "] Received token");

        // Send ACK receipt immediately
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
                    System.out.println("[Peer " + peer.ringPosition + "] Received regenerated token");
                }
            }
        }
        
        clientSocket.close();
        
        // Process all requests
        peer.processAllRequests();

        // Token still in circulation
        peer.lastTokenSeenTime.set(System.currentTimeMillis());
        
        // Forward token with ACK mechanism
        peer.hasToken = false;
        peer.forwardTokenWithAck(token);
        
    } catch(Exception e) {
        logger.severe("Token connection error: " + e.getMessage());
        e.printStackTrace();
    }
}
}
}