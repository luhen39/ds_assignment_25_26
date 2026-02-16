# Distributed Systems - Practical Assignment 2025/26

This project implements three fundamental distributed systems algorithms:
1. **Token Ring Algorithm** - Mutual exclusion
2. **Anti-Entropy (Gossip) Protocol** - P2P data aggregation
3. **Totally-Ordered Multicast (TOM)** - Consistent message delivery with security features

---

All **Extra Marks** features have been implemented.

---

## Project Structure

```
assignment/
├── ds/
│   └── assignment/
│       ├── p2p/              # Exercise 2: Anti-Entropy Gossip
│       │   ├── Peer4.java
│       │   ├── PoissonProcess.java
│       │   ├── graphs/       # Generated convergence analysis graphs
│       │   └── test1-4/      # Experimental topology data
│       ├── tom/              # Exercise 3: Totally-Ordered Multicast
│       │   ├── Peer.java
│       │   └── PoissonProcess.java
│       └── tring/            # Exercise 1: Token Ring
│           ├── Peer.java
│           ├── CalculatorServer.java
│           ├── TokenInjector.java
│           └── PoissonProcess.java
├── Shell Scripts/
│   ├── start_p2p.sh          # Exercise 2: Manual P2P network (6 peers)
│   ├── start_p2p_2.sh        # Exercise 2: Manual P2P network (6 peers - 5 with value 0 and 1 with value 1)
│   ├── build_random_network.sh  # Exercise 2: Automated convergence testing
│   ├── start_TOM.sh          # Exercise 3: TOM network launcher
│   ├── test_security.sh      # Exercise 3: Security validation (Extra Marks - Exercise 3)
│   ├── visual_compare.sh     # Exercise 3: Total ordering verification
│   ├── start_ring.sh         # Exercise 1: Token ring launcher
│   └── stop_all.sh           # All exercises: Unified shutdown
├── Python Scripts/
│   ├── plot_convergence.py         # Exercise 2: Multi-run convergence analysis
│   ├── plot_peer_convergence.py    # Exercise 2: Individual peer visualization
│   └── combined_plot.py            # Exercise 2: Comparison across 3 runs
├── Configuration/
│   ├── peers.config          # Exercise 1: Token ring topology
│   └── dictionary.txt        # Exercise 3: TOM word dictionary
├── data/                     # Exercise 2: Convergence results (CSV)
└── peer_plots/              # Exercise 2: Individual peer convergence graphs
```

**Note:** The file `Peer4.java` is named as such because it represents the fourth implementation version during development. It is not related to a "Peer 4" identifier in the actual system - all peers run the same `Peer4` class.

**Note 2:** The `data/` and `peer_plots/` directories are created automatically when running the convergence tests and plotting scripts.

---

## Compilation

From the project root directory:

```bash
# Compile all modules
javac ds/assignment/p2p/*.java
javac ds/assignment/tom/*.java
javac ds/assignment/tring/*.java
```

**Requirements:**
- Java 11 or higher
- Python 3.8+ with: `matplotlib`, `pandas`, `numpy`
- `tmux` (for interactive multi-window sessions)
- `bc` (for shell arithmetic in convergence testing)

**Install Python dependencies:**
```bash
pip3 install matplotlib pandas numpy
```

**Install tmux:**
```bash
# Ubuntu/Debian
sudo apt install tmux

# macOS
brew install tmux
```

---

## Running the Systems

### **Exercise 1: Token Ring (Mutual Exclusion)**

```bash
./start_ring.sh
```

**Configuration:** peers.config  
**Stop:** `./stop_all.sh`

**tmux navigation:**
- `Ctrl+B` then `0-6` to switch between windows

---

### **Exercise 2: P2P Anti-Entropy (Data Aggregation)**

**Implementation:** All peers in this exercise use the `Peer4.java` class (named after the fourth development iteration, not a peer identifier).

#### **Option A: Manual Network**
```bash
./start_p2p.sh
```

#### **Option B: Network Size Discovery**
```bash
./start_p2p_2.sh
```

#### **Option C: Automated Convergence Testing**
```bash
./build_random_network.sh <N> 
```

**Example:**
```bash
./build_random_network.sh 6 
```

**Tested network sizes:** N = {6, 10, 15, 20, 30}

**Clean up:**
```bash
pkill -f "Peer4"
rm localhost_*_peer4.log
```

#### **Visualization**

**Individual peer convergence:**
```bash
python3 plot_peer_convergence.py
```
- Creates plots in `peer_plots/` directory
- **Note:** Logging starts when the **first sync** happens

**Compare multiple runs:**
```bash
python3 combined_plot.py
```
- Compares convergence times across the first 3 experimental runs
- Used for consistency validation and complexity analysis

**Single-run analysis:**
```bash
python3 plot_convergence.py
```

---

### **Exercise 3: Total Order Multicast (TOM)**

#### **Normal Operation**

```bash
./start_TOM.sh
```

**Verify total ordering:**
```bash
# After running for 30+ seconds
pkill -f "ds.assignment.tom.Peer"
./visual_compare.sh
```

Expected: All peers show **identical word sequences**

#### **Security Validation (Extra Marks - Exercise 3)**

```bash
./test_security.sh
```

**Expected output:**
```
    (a) Timestamp/Message ID reuse detection: WORKING
    (b) Future timestamp detection: WORKING
    Detection success rate: 100.0%
```

---

## Cleaning Up

### **Stop All Systems**
```bash
./stop_all.sh
```

### **Remove Log Files**
```bash
rm *.log
rm localhost_*_peer*.log
```

### **Remove Compiled Classes**
```bash
find . -name "*.class" -type f -delete
```

### **Full Clean (includes data)**
```bash
rm -rf data/*.csv
rm -rf peer_plots/*.png
rm *.png
rm topology_*.txt
```

---

## Configuration Files

### **peers.config (Exercise 1)**
Token ring topology configuration

### **dictionary.txt (Exercise 3)**
Word list for TOM message generation

---

## Experimental Data

### **Anti-Entropy Convergence Results (Exercise 2)**

Located in `ds/assignment/p2p/test1-4/` directories:

- **test1/**: First experimental run
- **test2/**: Second experimental run  
- **test3/**: Third experimental run
- **test4/**: Additional validation run with individual peer convergence graphs

Each contains:
- `topology_N<size>.txt` - Network topology used
- `convergence_results.csv` - Timing and accuracy metrics


Additionally, the `ds/assignment/p2p/graphs/` directory contains generated convergence analysis graphs from the experimental runs.

**Note:** `combined_plot.py` compares the first three runs (test1-3) to validate consistency and analyze convergence behavior across multiple experiments.

---

## Implementation Details

### **P2P Anti-Entropy (Exercise 2)**
- **Synchronization rate:** 2 per minute (Poisson λ=2/60)
- **Convergence criterion:** StdDev < 0.001 & mean_error < 0.001
- **Logging note:** Timestamp logging in `peer_plots/` starts when the first sync occurs for each peer

### **Token Ring (Exercise 1)**
- **Token timeout:** 90 seconds
- **ACK timeout:** 3 seconds
- **Request generation:** 4 per minute (Poisson λ=4/60)

### **TOM Security (Exercise 3)**
- **Timestamp threshold:** 100 (future detection)
- **Message ID tracking:** ConcurrentHashMap for thread safety
- **Attack detection:** Both (a) and (b) achieve 100% detection

---

## Troubleshooting

### **Ports already in use:**
```bash
pkill -f "ds.assignment"
tmux kill-server
```

### **Python module not found:**
```bash
pip3 install matplotlib pandas numpy
```

### **tmux not found:**
```bash
sudo apt install tmux  # Ubuntu/Debian
brew install tmux      # macOS
```

### **Java version issues:**
```bash
java -version  # Should be 11+
```

### **Convergence not detected (Exercise 2):**
- Check logs for sync activity: `tail -f localhost_*_peer4.log | grep SYNC`
- Verify topology is connected: `cat topology_N*.txt`

### **TOM security test fails (Exercise 3):**
- Recompile: `javac ds/assignment/tom/*.java`
- Check if peers started: `ps aux | grep "ds.assignment.tom"`
- Verify log files exist: `ls -la localhost_*_peer.log`

---

## Author

**Distributed Systems Course 2025/26**  
Luís Ganço - (up202004196) - MERSI  
Faculty of Sciences, University of Porto

---
