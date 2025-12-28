package ds.assignment.p2p;

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
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import ds.assignment.p2p.PoissonProcess;

public class Peer4 {
    String host;
    int localPort;
    List<PeerInfo> knownPeers;
    Logger logger;
    double value;  // Single value, not a set
    Random rng;
    PoissonProcess syncProcess;  // 2 per minute = 2/60 per second
    Map<String, String> registeredPeers;  // Map of peer IDs to their addresses
    String peerId;

    public Peer4(String hostname, int localPort, double initialValue, List<PeerInfo> knownPeers) {
        this.host = hostname;
        this.localPort = localPort;
        this.value = initialValue;
        this.knownPeers = knownPeers;
        this.rng = new Random();
        this.syncProcess = new PoissonProcess(2.0 / 60.0, rng);  // 2 per minute
        this.registeredPeers = new ConcurrentHashMap<>();
        this.peerId = hostname + ":" + localPort;
        this.logger = Logger.getLogger("logfile");
        try {
            FileHandler handler = new FileHandler("./" + hostname + "_" + localPort + "_peer4.log", true);
            logger.addHandler(handler);
            SimpleFormatter formatter = new SimpleFormatter();
            handler.setFormatter(formatter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized double getValue() {
        return value;
    }

    public synchronized void setValue(double newValue) {
        this.value = newValue;
    }

    public synchronized void printValue() {
        System.out.printf("Peer %s has value: %.6f (registered peers: %d)\n", 
            peerId, value, registeredPeers.size());
        logger.info(String.format("Current value: %.6f, registered peers: %d", value, registeredPeers.size()));
    }

    public synchronized PeerInfo getRandomNeighbor() {
        if (knownPeers.isEmpty()) {
            return null;
        }
        int randomIndex = rng.nextInt(knownPeers.size());
        return knownPeers.get(randomIndex);
    }

    public synchronized void registerPeer(String peerId, String address) {
        registeredPeers.put(peerId, address);
        logger.info("Registered peer: " + peerId + " at " + address);
    }

    public synchronized int getNetworkSize() {
        // Including self
        return registeredPeers.size() + 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java Peer4 <hostname> <localPort> [<remoteHost1> <remotePort1> ...]");
            System.err.println("Example: java Peer4 localhost 10001 localhost 10002");
            System.err.println("Or with specific initial value: java Peer4 <hostname> <localPort> <initialValue> [<remoteHost1> <remotePort1> ...]");
            System.exit(1);
        }

        String hostname = args[0];
        int localPort = Integer.parseInt(args[1]);
        
        // Check if initial value is provided or generate random
        double initialValue;
        int peerStartIndex;
        
        // Check if third argument is a number (initial value) or a hostname (peer)
        if (args.length > 2) {
            try {
                initialValue = Double.parseDouble(args[2]);
                peerStartIndex = 3;
            } catch (NumberFormatException e) {
                // Third argument is not a number, so generate random value
                Random rng = new Random();
                initialValue = rng.nextDouble();  // Random value in (0, 1)
                peerStartIndex = 2;
            }
        } else {
            // No third argument, generate random value
            Random rng = new Random();
            initialValue = rng.nextDouble();
            peerStartIndex = 2;
        }

        // Validate that remaining arguments are in pairs (host port)
        if ((args.length - peerStartIndex) % 2 != 0) {
            System.err.println("Error: Remote peers must be specified in pairs (host port)");
            System.err.println("Usage: java Peer4 <hostname> <localPort> [<remoteHost1> <remotePort1> ...]");
            System.err.println("Or with specific initial value: java Peer4 <hostname> <localPort> <initialValue> [<remoteHost1> <remotePort1> ...]");
            System.exit(1);
        }

        // Parse known peers
        List<PeerInfo> knownPeers = new ArrayList<>();
        for (int i = peerStartIndex; i < args.length; i += 2) {
            String remoteHost = args[i];
            int remotePort = Integer.parseInt(args[i + 1]);
            knownPeers.add(new PeerInfo(remoteHost, remotePort));
        }

        Peer4 peer = new Peer4(hostname, localPort, initialValue, knownPeers);
        System.out.printf("Peer4 started @ %s:%d with initial value: %.6f\n", 
            hostname, localPort, initialValue);
        System.out.println("Known peers: " + knownPeers.size());
        for (PeerInfo pi : knownPeers) {
            System.out.println("  - " + pi);
        }

        // Start server thread to handle sync and registration requests
        new Thread(new AntiEntropyServer(peer)).start();

        // Register with all known peers
        new Thread(new PeerRegistration(peer)).start();

        // Start Anti-Entropy sync client thread
        new Thread(new AntiEntropyClient(peer)).start();
    }
}

class PeerInfo {
    String host;
    int port;

    public PeerInfo(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}

class PeerRegistration implements Runnable {
    Peer4 peer;

    public PeerRegistration(Peer4 peer) {
        this.peer = peer;
    }

    @Override
    public void run() {
        try {
            // Wait for server to start
            Thread.sleep(2000);

            for (PeerInfo knownPeer : peer.knownPeers) {
                try {
                    peer.logger.info("Registering with peer " + knownPeer);
                    Socket socket = new Socket(InetAddress.getByName(knownPeer.host), knownPeer.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Send REGISTER command
                    out.println("REGISTER");
                    out.println(peer.peerId);
                    out.println(peer.host + ":" + peer.localPort);

                    String response = in.readLine();
                    if ("OK".equals(response)) {
                        peer.logger.info("Successfully registered with " + knownPeer);
                    }

                    socket.close();
                } catch (Exception e) {
                    peer.logger.warning("Failed to register with " + knownPeer + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

class AntiEntropyServer implements Runnable {
    Peer4 peer;
    ServerSocket server;

    public AntiEntropyServer(Peer4 peer) throws Exception {
        this.peer = peer;
        this.server = new ServerSocket(peer.localPort, 10, InetAddress.getByName(peer.host));
    }

    @Override
    public void run() {
        try {
            peer.logger.info("AntiEntropyServer: running at port " + peer.localPort);
            while (true) {
                try {
                    Socket client = server.accept();
                    // Handle each request in a separate thread
                    new Thread(new RequestHandler(peer, client)).start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class RequestHandler implements Runnable {
    Peer4 peer;
    Socket client;

    public RequestHandler(Peer4 peer, Socket client) {
        this.peer = peer;
        this.client = client;
    }

    @Override
    public void run() {
        try {
            String clientAddress = client.getInetAddress().getHostAddress();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true);

            String command = in.readLine();

            if ("REGISTER".equals(command)) {
                String peerId = in.readLine();
                String address = in.readLine();
                peer.registerPeer(peerId, address);
                out.println("OK");
                peer.logger.info("Registered peer: " + peerId);
            } 
            else if ("SYNC".equals(command)) {
                // ATOMIC EXCHANGE: Lock to prevent concurrent syncs
                synchronized(peer) {
                    // Read remote peer's value
                    double remoteValue = Double.parseDouble(in.readLine());
                    
                    // Get current value
                    double currentValue = peer.getValue();
                    
                    // Send back the current value BEFORE updating
                    out.println(currentValue);
                    out.flush();
                    
                    // Calculate average
                    double newValue = (currentValue + remoteValue) / 2.0;
                    
                    // Update local value
                    peer.setValue(newValue);
                    
                    peer.logger.info(String.format("AntiEntropyServer: SYNC from %s - old=%.6f, remote=%.6f, new=%.6f", 
                        clientAddress, currentValue, remoteValue, newValue));
                    peer.printValue();
                }
            }
            else if ("GET_SIZE".equals(command)) {
                out.println(peer.getNetworkSize());
            }

            client.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class AntiEntropyClient implements Runnable {
    Peer4 peer;
    PoissonProcess syncProcess;

    public AntiEntropyClient(Peer4 peer) {
        this.peer = peer;
        this.syncProcess = new PoissonProcess(2.0 / 60.0, new Random());
    }

    @Override
    public void run() {
        try {
            peer.logger.info("AntiEntropyClient: started (2 syncs per minute)");
            Thread.sleep(5000);

            while (true) {
                double waitTime = syncProcess.timeForNextEvent();
                Thread.sleep((long) (waitTime * 1000));

                PeerInfo neighbor = peer.getRandomNeighbor();

                if (neighbor == null) {
                    peer.logger.warning("AntiEntropyClient: no neighbors available");
                    continue;
                }

                try {
                    peer.logger.info("AntiEntropyClient: initiating Anti-Entropy with " + neighbor);

                    Socket socket = new Socket(InetAddress.getByName(neighbor.host), neighbor.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // ATOMIC EXCHANGE: Lock to prevent concurrent syncs
                    synchronized(peer) {
                        // Send SYNC command
                        out.println("SYNC");
                        
                        // Get current value
                        double currentValue = peer.getValue();
                        
                        // Send current value
                        out.println(currentValue);
                        out.flush();

                        // Receive remote peer's OLD value (before it averaged)
                        double remoteValue = Double.parseDouble(in.readLine());

                        // Calculate average
                        double newValue = (currentValue + remoteValue) / 2.0;

                        // Update local value
                        peer.setValue(newValue);

                        peer.logger.info(String.format("AntiEntropyClient: SYNC with %s - old=%.6f, received=%.6f, new=%.6f", 
                            neighbor, currentValue, remoteValue, newValue));
                        peer.printValue();
                    }

                    socket.close();
                } catch (Exception e) {
                    peer.logger.warning("AntiEntropyClient: failed to sync with " + neighbor + " - " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}