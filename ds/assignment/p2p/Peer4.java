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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import ds.assignment.p2p.PoissonProcess;

public class Peer4 {
    String host;
    int localPort;
    List<PeerInfo> knownPeers;
    Logger logger;
    private double value;  // Protected by valueLock
    private final Object valueLock = new Object();  // Dedicated lock for value
    PoissonProcess syncProcess;
    Map<String, String> registeredPeers;
    String peerId;

    public Peer4(String hostname, int localPort, double initialValue, List<PeerInfo> knownPeers) {
        // Force English locale for consistent log timestamps
        System.setProperty("user.language", "en");
        System.setProperty("user.country", "US");
        this.host = hostname;
        this.localPort = localPort;
        this.value = initialValue;
        this.knownPeers = new CopyOnWriteArrayList<>(knownPeers);
        this.syncProcess = new PoissonProcess(2.0 / 60.0, new Random());
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

    public double getValue() {
        synchronized(valueLock) {
            return value;
        }
    }

    public void setValue(double newValue) {
        synchronized(valueLock) {
            this.value = newValue;
        }
    }

    /**
     * Atomic exchange for server side: returns old value, updates to average
     */
    public double exchangeAsServer(double remoteValue) {
        synchronized(valueLock) {
            double oldValue = this.value;
            this.value = (this.value + remoteValue) / 2.0;
            return oldValue;
        }
    }

    /**
     * Atomic exchange for client side: updates to average
     */
    public void exchangeAsClient(double remoteValue) {
        synchronized(valueLock) {
            this.value = (this.value + remoteValue) / 2.0;
        }
    }

    public void printValue() {
        synchronized(valueLock) {
            System.out.printf("Peer %s has value: %.6f (registered peers: %d)\n", 
                peerId, value, registeredPeers.size());
            logger.info(String.format("Current value: %.6f, registered peers: %d", 
                value, registeredPeers.size()));
        }
    }

    public PeerInfo getRandomNeighbor() {
        if (knownPeers.isEmpty()) {
            return null;
        }
        int randomIndex = ThreadLocalRandom.current().nextInt(knownPeers.size());
        return knownPeers.get(randomIndex);
    }

    public void registerPeer(String peerId, String address) {
        registeredPeers.put(peerId, address);
        logger.info("Registered peer: " + peerId + " at " + address);
    }

    public int getNetworkSize() {
        return registeredPeers.size() + 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java Peer4 <hostname> <localPort> [<initialValue>] [<remoteHost1> <remotePort1> ...]");
            System.err.println("Example: java Peer4 localhost 10001 0.5 localhost 10002");
            System.exit(1);
        }

        String hostname = args[0];
        int localPort = Integer.parseInt(args[1]);
        
        double initialValue;
        int peerStartIndex;
        
        if (args.length > 2) {
            try {
                initialValue = Double.parseDouble(args[2]);
                peerStartIndex = 3;
            } catch (NumberFormatException e) {
                initialValue = ThreadLocalRandom.current().nextDouble();
                peerStartIndex = 2;
            }
        } else {
            initialValue = ThreadLocalRandom.current().nextDouble();
            peerStartIndex = 2;
        }

        if ((args.length - peerStartIndex) % 2 != 0) {
            System.err.println("Error: Remote peers must be specified in pairs (host port)");
            System.exit(1);
        }

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

        new Thread(new AntiEntropyServer(peer)).start();
        new Thread(new PeerRegistration(peer)).start();
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
            Thread.sleep(2000);

            for (PeerInfo knownPeer : peer.knownPeers) {
                try {
                    peer.logger.info("Registering with peer " + knownPeer);
                    Socket socket = new Socket(InetAddress.getByName(knownPeer.host), knownPeer.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

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
            peer.logger.warning("PeerRegistration interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
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
                    new Thread(new RequestHandler(peer, client)).start();
                } catch (Exception e) {
                    peer.logger.warning("AntiEntropyServer error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            peer.logger.severe("AntiEntropyServer fatal error: " + e.getMessage());
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
            
            if (command == null) {
                peer.logger.warning("RequestHandler: received null command from " + clientAddress);
                client.close();
                return;
            }

            if ("REGISTER".equals(command)) {
                String peerId = in.readLine();
                String address = in.readLine();
                if (peerId != null && address != null) {
                    peer.registerPeer(peerId, address);
                    out.println("OK");
                } else {
                    peer.logger.warning("Invalid REGISTER command from " + clientAddress);
                }
            } 
            else if ("SYNC".equals(command)) {
                //  Read remote value OUTSIDE lock (non-blocking I/O)
                String remoteValueStr = in.readLine();
                if (remoteValueStr == null) {
                    peer.logger.warning("RequestHandler: received null value during SYNC from " + clientAddress);
                    client.close();
                    return;
                }
                
                double remoteValue = Double.parseDouble(remoteValueStr);
                
                // Atomic exchange - lock only for value update
                double oldValue = peer.exchangeAsServer(remoteValue);
                
                // Send response OUTSIDE lock (non-blocking I/O)
                out.println(oldValue);
                out.flush();
                
                peer.logger.info(String.format("AntiEntropyServer: SYNC from %s - old=%.6f, remote=%.6f, new=%.6f", 
                    clientAddress, oldValue, remoteValue, (oldValue + remoteValue) / 2.0));
                
                peer.printValue();
            }
            else if ("GET_SIZE".equals(command)) {
                out.println(peer.getNetworkSize());
            }
            else {
                peer.logger.warning("RequestHandler: unknown command '" + command + "' from " + clientAddress);
            }

            client.close();
        } catch (Exception e) {
            peer.logger.warning("RequestHandler error: " + e.getMessage());
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

                    //  Connect OUTSIDE any lock (non-blocking network setup)
                    Socket socket = new Socket(InetAddress.getByName(neighbor.host), neighbor.port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Read current value atomically just before sending
                    double currentValue = peer.getValue();
                    
                    // Send SYNC command and value (no lock, pure I/O)
                    out.println("SYNC");
                    out.println(currentValue);
                    out.flush();

                    // Read response OUTSIDE any lock (non-blocking I/O)
                    String remoteValueStr = in.readLine();
                    if (remoteValueStr == null) {
                        peer.logger.warning("AntiEntropyClient: received null response from " + neighbor);
                        socket.close();
                        continue;
                    }
                    
                    double remoteValue = Double.parseDouble(remoteValueStr);

                    // Atomic update - lock only for value change
                    peer.exchangeAsClient(remoteValue);
                    
                    peer.logger.info(String.format("AntiEntropyClient: SYNC with %s - sent=%.6f, received=%.6f, new=%.6f", 
                        neighbor, currentValue, remoteValue, (currentValue + remoteValue) / 2.0));
                    
                    peer.printValue();

                    socket.close();
                } catch (Exception e) {
                    peer.logger.warning("AntiEntropyClient: failed to sync with " + neighbor + " - " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            peer.logger.warning("AntiEntropyClient interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}