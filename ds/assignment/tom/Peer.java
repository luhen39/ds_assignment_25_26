package ds.assignment.tom;

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
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import ds.assignment.tom.PoissonProcess;

public class Peer {
    String host;
    int localPort;
    int processId;
    List<PeerInfo> allPeers;
    Logger logger;
    
    // Lamport clock
    private AtomicInteger lamportClock = new AtomicInteger(0);
    
    // Message queue for TOM (ordered by timestamp, then by processId)
    private PriorityQueue<Message> holdbackQueue;
    private final Object queueLock = new Object();
    
    // Track the latest timestamp seen from each peer
    private Map<Integer, Integer> latestTimestamps;
    
    // EXTRA MARKS: Security features
    private Map<String, Integer> seenMessageIds = new ConcurrentHashMap<>();  // messageId -> timestamp
    private Map<Integer, Set<Integer>> seenTimestampsPerSender = new ConcurrentHashMap<>();  // senderId -> Set of timestamps
    private static final int MAX_FUTURE_TIMESTAMP_THRESHOLD = 100;  // Detect "writing in the future"
    
    // Word generation
    PoissonProcess wordProcess;
    private List<String> dictionary;
    private List<String> printedWords;
    
    // Control and statistics
    volatile boolean running = true;
    private long startTime;

    public Peer(String hostname, int localPort, int processId, List<PeerInfo> allPeers) {
        // Force English locale for consistent log timestamps
        System.setProperty("user.language", "en");
        System.setProperty("user.country", "US");
        this.host = hostname;
        this.localPort = localPort;
        this.processId = processId;
        this.allPeers = new CopyOnWriteArrayList<>(allPeers);
        this.holdbackQueue = new PriorityQueue<>();
        this.latestTimestamps = new ConcurrentHashMap<>();
        this.wordProcess = new PoissonProcess(1.0, new Random()); // 1 word per second
        this.dictionary = new ArrayList<>();
        this.printedWords = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
        
        // Initialize latest timestamps to 0 for all peers
        for (PeerInfo peer : allPeers) {
            latestTimestamps.put(peer.processId, 0);
        }
        
        this.logger = Logger.getLogger("logfile");
        try {
            FileHandler handler = new FileHandler("./" + hostname + "_" + localPort + "_peer.log", true);
            logger.addHandler(handler);
            SimpleFormatter formatter = new SimpleFormatter();
            handler.setFormatter(formatter);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        loadDictionary();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            System.out.println("\n[Peer " + processId + "] Shutting down...");
            try {
                Thread.sleep(1000); // Give time for pending messages
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            printStatistics();
        }));
    }

    private void loadDictionary() {
        try {
            // Try multiple locations
            java.nio.file.Path[] paths = {
                java.nio.file.Paths.get("dictionary.txt"),                    // Project root
                java.nio.file.Paths.get("ds/assignment/tom/dictionary.txt")   // Source directory
            };
            
            for (java.nio.file.Path path : paths) {
                if (java.nio.file.Files.exists(path)) {
                    List<String> lines = java.nio.file.Files.readAllLines(path);
                    for (String line : lines) {
                        String word = line.trim().toLowerCase();
                        if (!word.isEmpty() && !word.startsWith("#")) {
                            dictionary.add(word);
                        }
                    }
                    logger.info("Loaded dictionary from " + path + " with " + dictionary.size() + " words");
                    if (!dictionary.isEmpty()) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Could not load dictionary from file: " + e.getMessage());
        }
        
        // Fallback to hardcoded dictionary
        String[] words = {"distributed", "systems", "algorithm", "consensus", "multicast", 
                         "lamport", "clock", "timestamp", "ordering", "message",
                         "synchronization", "protocol", "network", "communication", "peer",
                         "byzantine", "fault", "tolerance", "replication", "consistency"};
        for (String word : words) {
            dictionary.add(word);
        }
        logger.info("Loaded hardcoded dictionary with " + dictionary.size() + " words");
    }

    String getRandomWord() {
        Random rand = new Random();
        return dictionary.get(rand.nextInt(dictionary.size()));
    }

    private int incrementClock() {
        return lamportClock.incrementAndGet();
    }

    private void updateClock(int receivedTimestamp) {
        lamportClock.updateAndGet(current -> Math.max(current, receivedTimestamp) + 1);
    }

    // Multicast message to all other peers
    public void multicast(String word) {
        int timestamp = incrementClock();
        String messageId = processId + ":" + timestamp;
        
        Message msg = new Message(word, timestamp, processId, messageId);
        
        logger.info(String.format("Multicasting word '%s' with timestamp %d", word, timestamp));
        
        // Add to own holdback queue
        synchronized(queueLock) {
            holdbackQueue.offer(msg);
        }
        
        // Update own latest timestamp
        latestTimestamps.put(processId, timestamp);
        
        // Send MESSAGE to all other peers
        for (PeerInfo peer : allPeers) {
            if (peer.processId == this.processId) continue;
            
            final PeerInfo targetPeer = peer;
            new Thread(() -> {
                try {
                    Socket socket = new Socket(InetAddress.getByName(targetPeer.host), targetPeer.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    
                    out.println("MESSAGE");
                    out.println(messageId);
                    out.println(word);
                    out.println(timestamp);
                    out.println(processId);
                    
                    socket.close();
                    logger.info("Sent message to peer " + targetPeer.processId);
                } catch (Exception e) {
                    logger.warning("Failed to send to peer " + targetPeer.processId + ": " + e.getMessage());
                }
            }).start();
        }
        
        // Sender also multicasts ACK to all other peers
        sendAcknowledgmentToAll(messageId, timestamp);
        
        // Try to deliver messages
        tryDeliverMessages();
    }

    void handleReceivedMessage(String messageId, String word, int timestamp, int senderId) {
    // Message ID replay detection
    if (seenMessageIds.containsKey(messageId)) {
        int previousTimestamp = seenMessageIds.get(messageId);
        if (previousTimestamp != timestamp) {
            // Different timestamp for same message ID = "rewriting history" attack
            logger.severe(String.format("SECURITY ALERT (a): Message ID '%s' reused with different timestamp (previous=%d, new=%d)", 
                                       messageId, previousTimestamp, timestamp));
            logger.severe(String.format("[Peer %d] REJECTED malicious message: %s (message ID reuse detected)", 
                                       processId, messageId));
            System.err.println(String.format("[Peer %d] REJECTED malicious message: %s (message ID reuse detected)", 
                                            processId, messageId));
            return;  // REJECT the message
        } else {
            // Same message ID + same timestamp = replay attack (EXACT DUPLICATE)
            logger.severe(String.format("SECURITY ALERT (a): Replay attack detected for message '%s' (timestamp=%d)", 
                                       messageId, timestamp));
            logger.severe(String.format("[Peer %d] REJECTED malicious message: %s (replay attack detected)", 
                                       processId, messageId));
            System.err.println(String.format("[Peer %d] REJECTED malicious message: %s (replay attack detected)", 
                                            processId, messageId));
            return;  // REJECT the message
        }
    }
    
        // Timestamp reuse detection
        seenTimestampsPerSender.putIfAbsent(senderId, ConcurrentHashMap.newKeySet());
        Set<Integer> senderTimestamps = seenTimestampsPerSender.get(senderId);
        
        if (senderTimestamps.contains(timestamp)) {
            // This sender already used this timestamp = "rewriting history" attack
            logger.severe(String.format("SECURITY ALERT (a): Timestamp %d already used by sender %d (message ID: %s)", 
                                    timestamp, senderId, messageId));
            logger.severe(String.format("[Peer %d] REJECTED malicious message: %s (timestamp reuse detected)", 
                                    processId, messageId));
            System.err.println(String.format("[Peer %d] REJECTED malicious message: %s (timestamp reuse detected)", 
                                            processId, messageId));
            return;  // REJECT the message
        }

        // Future timestamp detection
        int currentClock = lamportClock.get();
        if (timestamp > currentClock + MAX_FUTURE_TIMESTAMP_THRESHOLD) {
            // Timestamp is far in the future = "writing in the future" attack
            logger.severe(String.format("SECURITY ALERT (b): Future timestamp detected (message_ts=%d, current_clock=%d, difference=%d)", 
                                    timestamp, currentClock, timestamp - currentClock));
            logger.severe(String.format("[Peer %d] REJECTED malicious message: %s (future timestamp detected)", 
                                    processId, messageId));
            System.err.println(String.format("[Peer %d] REJECTED malicious message: %s (future timestamp detected)", 
                                            processId, messageId));
            return;  // REJECT the message
        }
        
        seenMessageIds.put(messageId, timestamp);
        senderTimestamps.add(timestamp);  // Track this timestamp for this sender
        
        updateClock(timestamp);
        
        // Update latest timestamp from sender
        latestTimestamps.compute(senderId, (k, v) -> v == null ? timestamp : Math.max(v, timestamp));
        
        Message msg = new Message(word, timestamp, senderId, messageId);
        
        synchronized(queueLock) {
            holdbackQueue.offer(msg);
        }
        
        logger.info(String.format("Received message '%s' from peer %d with timestamp %d", 
                    word, senderId, timestamp));
        
        // Send acknowledgment to all peers
        sendAcknowledgmentToAll(messageId, timestamp);
        
        // Try to deliver messages
        tryDeliverMessages();
    }

    private void sendAcknowledgmentToAll(String messageId, int timestamp) {
        // Send ACK to ALL other peers (TOM requirement)
        for (PeerInfo peer : allPeers) {
            if (peer.processId == this.processId) continue; // Don't send to self
            
            final PeerInfo targetPeer = peer;
            new Thread(() -> {
                try {
                    Socket socket = new Socket(InetAddress.getByName(targetPeer.host), targetPeer.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    
                    out.println("ACK");
                    out.println(messageId);
                    out.println(timestamp);
                    out.println(processId);
                    
                    socket.close();
                    logger.info("Sent ACK for " + messageId + " to peer " + targetPeer.processId);
                } catch (Exception e) {
                    logger.warning("Failed to send ACK to peer " + targetPeer.processId + ": " + e.getMessage());
                }
            }).start();
        }
    }

    void handleAcknowledgment(String messageId, int timestamp, int ackSenderId) {
        // Update latest timestamp from ACK sender
        latestTimestamps.compute(ackSenderId, (k, v) -> v == null ? timestamp : Math.max(v, timestamp));
        
        logger.info("Received ACK for " + messageId + " from peer " + ackSenderId + " (ts=" + timestamp + ")");
        
        // Try to deliver messages
        tryDeliverMessages();
    }

    private void tryDeliverMessages() {
        synchronized(queueLock) {
            while (!holdbackQueue.isEmpty()) {
                Message msg = holdbackQueue.peek();
                
                // TOM delivery condition: For each peer, we must have seen
                // a message/ACK with timestamp >= msg.timestamp
                boolean canDeliver = true;
                
                for (PeerInfo peer : allPeers) {
                    Integer lastSeen = latestTimestamps.get(peer.processId);
                    
                    if (lastSeen == null || lastSeen < msg.timestamp) {
                        // Haven't seen a message/ACK from this peer with timestamp >= msg.timestamp
                        canDeliver = false;
                        logger.fine(String.format("Cannot deliver %s: waiting for peer %d (lastSeen=%d, msgTs=%d)",
                                   msg.messageId, peer.processId, lastSeen, msg.timestamp));
                        break;
                    }
                }
                
                if (canDeliver) {
                    // All peers have sent a message/ACK with timestamp >= msg.timestamp
                    holdbackQueue.poll();
                    deliverMessage(msg);
                } else {
                    // Can't deliver head message, stop trying
                    break;
                }
            }
        }
    }

    private void deliverMessage(Message msg) {
        printedWords.add(msg.content);
        System.out.printf("[Peer %d] Word #%d: %s (timestamp=%d, sender=%d)\n", 
                         processId, printedWords.size(), msg.content, msg.timestamp, msg.senderId);
        logger.info(String.format("Delivered: %s (ts=%d, sender=%d)", 
                    msg.content, msg.timestamp, msg.senderId));
    }

    public void printPeerTable() {
        System.out.println("\n[Peer " + processId + "] Network Topology - IP Address Table:");
        System.out.println("  " + "=".repeat(60));
        System.out.printf("  %-12s | %-20s | %-8s | %s\n", "Process ID", "Host", "Port", "Status");
        System.out.println("  " + "-".repeat(60));
        for (PeerInfo peer : allPeers) {
            String status = (peer.processId == this.processId) ? "SELF" : "PEER";
            System.out.printf("  %-12d | %-20s | %-8d | %s\n", 
                peer.processId, peer.host, peer.port, status);
        }
        System.out.println("  " + "=".repeat(60) + "\n");
    }

    public void printWordSequence() {
        System.out.println("\n[Peer " + processId + "] Final word sequence:");
        if (printedWords.isEmpty()) {
            System.out.println("  (no words delivered yet)");
        } else {
            for (int i = 0; i < printedWords.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + printedWords.get(i));
            }
        }
    }

    public void printStatistics() {
        long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  PEER " + processId + " - FINAL STATISTICS");
        System.out.println("=".repeat(70));
        System.out.println("  Running time:           " + elapsedSeconds + " seconds");
        System.out.println("  Total words delivered:  " + printedWords.size());
        System.out.println("  Final Lamport clock:    " + lamportClock.get());
        System.out.println("  Messages in queue:      " + holdbackQueue.size());
        System.out.println("  Dictionary size:        " + dictionary.size() + " words");
        System.out.println("  Network peers:          " + allPeers.size());
        System.out.println("-".repeat(70));
        
        // Print timestamp tracking
        System.out.println("  Latest timestamps seen from each peer:");
        for (PeerInfo peer : allPeers) {
            Integer ts = latestTimestamps.get(peer.processId);
            System.out.printf("    Peer %d: timestamp = %d\n", peer.processId, (ts == null ? 0 : ts));
        }
        System.out.println("=".repeat(70));
        
        printWordSequence();
        System.out.println("=".repeat(70) + "\n");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java Peer <hostname> <localPort> <processId> [<peerHost1> <peerPort1> <peerId1> ...]");
            System.err.println("Example: java Peer localhost 10001 1 localhost 10002 2 localhost 10003 3");
            System.exit(1);
        }

        String hostname = args[0];
        int localPort = Integer.parseInt(args[1]);
        int processId = Integer.parseInt(args[2]);

        if ((args.length - 3) % 3 != 0) {
            System.err.println("Error: Peers must be specified in triplets (host port id)");
            System.exit(1);
        }

        List<PeerInfo> allPeers = new ArrayList<>();
        // Add self
        allPeers.add(new PeerInfo(hostname, localPort, processId));
        
        // Add other peers
        for (int i = 3; i < args.length; i += 3) {
            String peerHost = args[i];
            int peerPort = Integer.parseInt(args[i + 1]);
            int peerId = Integer.parseInt(args[i + 2]);
            allPeers.add(new PeerInfo(peerHost, peerPort, peerId));
        }

        Peer peer = new Peer(hostname, localPort, processId, allPeers);
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TOTALLY-ORDERED MULTICAST (TOM) PEER");
        System.out.println("=".repeat(70));
        System.out.printf("  Peer ID:      %d\n", processId);
        System.out.printf("  Address:      %s:%d\n", hostname, localPort);
        System.out.printf("  Total peers:  %d\n", allPeers.size());
        System.out.println("=".repeat(70));

        // Display peer table
        peer.printPeerTable();

        // Start message receiver
        new Thread(new MessageServer(peer)).start();
        System.out.println("[Peer " + processId + "] Message server started");
        
        // Wait for network to stabilize
        System.out.println("[Peer " + processId + "] Waiting 3 seconds for network to stabilize...");
        Thread.sleep(3000);
        
        // Start word generator
        new Thread(new WordGenerator(peer)).start();
        System.out.println("[Peer " + processId + "] Word generator started (1 word/second using Poisson process)");
        System.out.println("[Peer " + processId + "] Press Ctrl+C to stop\n");
    }
}

class PeerInfo {
    String host;
    int port;
    int processId;

    public PeerInfo(String host, int port, int processId) {
        this.host = host;
        this.port = port;
        this.processId = processId;
    }

    @Override
    public String toString() {
        return "Peer" + processId + "@" + host + ":" + port;
    }
}

class Message implements Comparable<Message> {
    String content;
    int timestamp;
    int senderId;
    String messageId;

    public Message(String content, int timestamp, int senderId, String messageId) {
        this.content = content;
        this.timestamp = timestamp;
        this.senderId = senderId;
        this.messageId = messageId;
    }

    @Override
    public int compareTo(Message other) {
        // Order by timestamp, then by senderId for deterministic ordering
        if (this.timestamp != other.timestamp) {
            return Integer.compare(this.timestamp, other.timestamp);
        }
        return Integer.compare(this.senderId, other.senderId);
    }
}

class WordGenerator implements Runnable {
    Peer peer;

    public WordGenerator(Peer peer) {
        this.peer = peer;
    }

    @Override
    public void run() {
        try {
            peer.logger.info("WordGenerator started (1 word per second)");
            
            while (peer.running) {
                double waitTime = peer.wordProcess.timeForNextEvent();
                Thread.sleep((long) (waitTime * 1000));

                if (peer.running) {
                    String word = peer.getRandomWord();
                    peer.multicast(word);
                }
            }
            peer.logger.info("WordGenerator stopped");
        } catch (InterruptedException e) {
            peer.logger.info("WordGenerator interrupted");
            Thread.currentThread().interrupt();
        }
    }
}

class MessageServer implements Runnable {
    Peer peer;
    ServerSocket server;

    public MessageServer(Peer peer) throws Exception {
        this.peer = peer;
        this.server = new ServerSocket(peer.localPort, 10, InetAddress.getByName(peer.host));
    }

    @Override
    public void run() {
        try {
            peer.logger.info("MessageServer listening on port " + peer.localPort);
            while (peer.running) {
                try {
                    Socket client = server.accept();
                    new Thread(new MessageHandler(peer, client)).start();
                } catch (Exception e) {
                    if (peer.running) {
                        peer.logger.warning("MessageServer error: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            peer.logger.severe("MessageServer fatal error: " + e.getMessage());
        } finally {
            try {
                server.close();
            } catch (Exception e) {
                peer.logger.warning("Error closing server socket: " + e.getMessage());
            }
        }
    }
}

class MessageHandler implements Runnable {
    Peer peer;
    Socket client;

    public MessageHandler(Peer peer, Socket client) {
        this.peer = peer;
        this.client = client;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String command = in.readLine();
            
            if ("MESSAGE".equals(command)) {
                String messageId = in.readLine();
                String word = in.readLine();
                int timestamp = Integer.parseInt(in.readLine());
                int senderId = Integer.parseInt(in.readLine());
                
                peer.handleReceivedMessage(messageId, word, timestamp, senderId);
            } 
            else if ("ACK".equals(command)) {
                String messageId = in.readLine();
                int timestamp = Integer.parseInt(in.readLine());
                int ackSenderId = Integer.parseInt(in.readLine());
                
                peer.handleAcknowledgment(messageId, timestamp, ackSenderId);
            }

            client.close();
        } catch (Exception e) {
            peer.logger.warning("MessageHandler error: " + e.getMessage());
        }
    }
}