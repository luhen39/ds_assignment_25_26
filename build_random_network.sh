#!/bin/bash
# filepath: build_random_network.sh

# Script to build a connected network of N peers with random topology
# Runs peers as background processes and monitors convergence
# Automatically stops when StdDev < 0.001 & mean_error < 0.001 and saves results
# Usage: ./build_random_network.sh <N> [base_port]
# Example: ./build_random_network.sh 10 10001 (base_port is optional)

export LC_NUMERIC=C

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <N> [base_port]"
    echo "Example: $0 10 10001"
    echo "Creates N peers with random connected topology as background processes"
    echo "All peers initialized to 0.0 except last peer (1.0)"
    echo "Automatically stops when convergence is reached (StdDev < 0.001 & mean_error < 0.001)"
    exit 1
fi

N=$1
BASE_PORT=${2:-10001}

# Create data directory if it doesn't exist
mkdir -p data
OUTPUT_FILE="data/convergence_results.csv"
START_TIME=$(date +%s)

# Create CSV header if file doesn't exist
if [ ! -f "$OUTPUT_FILE" ]; then
    echo "N,ConvergenceTime(seconds),FinalAverage,ExpectedAverage,StdDev" > "$OUTPUT_FILE"
fi

# Function to save results on exit
save_results() {
    echo ""
    echo "========================================="
    echo "Saving convergence results..."
    
    # Extract final values from logs
    values=""
    for log in localhost_*_peer4.log; do
        if [ -f "$log" ]; then
            # Get the last "Current value:" line from this peer's log
            latest=$(grep "Current value:" "$log" | tail -n 1 | awk '{print $4}' | sed 's/,$//' | tr ',' '.')
            if [ -n "$latest" ]; then
                values="$values$latest\n"
            fi
        fi
    done
    values=$(echo -e "$values" | head -n $N)  # Ensure exactly N values
    value_count=$(echo "$values" | wc -l)
    
    echo "Debug: Collected $value_count values for N=$N"
    echo "Debug: Values: $values"
    
    if [ $value_count -lt $N ]; then
        echo "Warning: Only $value_count/$N peers have logged values"
    fi
    
    # Calculate statistics
    if [ -n "$values" ]; then
        stats=$(echo "$values" | awk '{
            sum += $1
            sumsq += $1*$1
        }
        END {
            mean = sum/NR
            variance = (sumsq/NR) - (mean*mean)
            stddev = sqrt(variance)
            printf "%.6f %.6f", mean, stddev
        }')
        
        mean=$(echo $stats | awk '{print $1}')
        std_dev=$(echo $stats | awk '{print $2}')
        ELAPSED=$(($(date +%s) - START_TIME))
        EXPECTED=$(echo "scale=6; 1.0/$N" | bc -l)
        
        # Save to CSV
        echo "$N,$ELAPSED,$mean,$EXPECTED,$std_dev" >> "$OUTPUT_FILE"
        
        echo "Results saved to $OUTPUT_FILE:"
        echo "N=$N, Time=${ELAPSED}s, Mean=$mean, Expected=$EXPECTED, StdDev=$std_dev"
    else
        echo "No values found in logs!"
    fi
    
    # Kill processes
    pkill -f "Peer4" 2>/dev/null
    echo "Network stopped."
    echo "========================================="
    exit 0
}

# Kill any existing peer processes
pkill -f "Peer4" 2>/dev/null
sleep 2

echo "========================================="
echo "Building Random Network for N=$N peers"
echo "========================================="
echo "Automatically stops when StdDev < 0.001 & mean_error < 0.001"

# Create adjacency lists for random connected topology
declare -a ADJACENCY

# Initialize empty adjacency lists
for ((i=0; i<N; i++)); do
    ADJACENCY[$i]=""
done

# Build random spanning tree (ensures connectivity)
echo "Generating random spanning tree..."
for ((i=1; i<N; i++)); do
    # Connect peer i to a random existing peer (0 to i-1)
    parent=$((RANDOM % i))
    
    # Add bidirectional edge
    ADJACENCY[$parent]="${ADJACENCY[$parent]} $i"
    ADJACENCY[$i]="${ADJACENCY[$i]} $parent"
    
    echo "  Edge: Peer $i <-> Peer $parent"
done

# Add random extra edges for richer connectivity
EXTRA_EDGES=$((N / 2))
echo "Adding $EXTRA_EDGES random extra edges..."

for ((e=0; e<EXTRA_EDGES; e++)); do
    peer1=$((RANDOM % N))
    peer2=$((RANDOM % N))
    
    # Avoid self-loops
    if [ $peer1 -eq $peer2 ]; then
        continue
    fi
    
    # Check if edge already exists
    if echo "${ADJACENCY[$peer1]}" | grep -wq "$peer2"; then
        continue
    fi
    
    # Add bidirectional edge
    ADJACENCY[$peer1]="${ADJACENCY[$peer1]} $peer2"
    ADJACENCY[$peer2]="${ADJACENCY[$peer2]} $peer1"
    
    echo "  Extra edge: Peer $peer1 <-> Peer $peer2"
done

# Save topology to file
TOPOLOGY_FILE="topology_N${N}.txt"
echo "Saving topology to $TOPOLOGY_FILE..."
> $TOPOLOGY_FILE
for ((i=0; i<N; i++)); do
    neighbors=$(echo "${ADJACENCY[$i]}" | xargs)
    echo "Peer $i: neighbors=[$neighbors]" >> $TOPOLOGY_FILE
done

# Start peers as background processes
echo ""
echo "Starting $N peers as background processes..."

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

for ((i=0; i<N; i++)); do
    port=$((BASE_PORT + i))
    
    # Build neighbor arguments
    neighbor_args=""
    for neighbor in ${ADJACENCY[$i]}; do
        neighbor_port=$((BASE_PORT + neighbor))
        neighbor_args="$neighbor_args localhost $neighbor_port"
    done
    
    # Last peer gets 1.0, others get 0.0
    if [ $i -eq $((N-1)) ]; then
        initial_value="1.0"
    else
        initial_value="0.0"
    fi
    
    # Start peer in background
    java ds.assignment.p2p.Peer4 localhost $port $initial_value $neighbor_args > /dev/null 2>&1 &
    
    echo "Peer $i: port=$port, neighbors=[${ADJACENCY[$i]}], initial=$initial_value, PID=$!"
done

echo ""
echo "========================================="
echo "Network created with N=$N peers"
echo "Expected convergence: 1/$N = $(echo "scale=6; 1.0/$N" | bc -l)"
echo "Topology saved to: $TOPOLOGY_FILE"
echo ""
echo "Monitor convergence:"
echo "  tail -f localhost_*_peer4.log | grep 'Current value'"
echo ""
echo "Script will automatically stop when StdDev < 0.001 & mean_error < 0.001"
echo "========================================="

# Initial status print
echo "Waiting for initial syncs..."
sleep 5  # Give time for first syncs
values=""
for log in localhost_*_peer4.log; do
    if [ -f "$log" ]; then
        latest=$(grep "Current value:" "$log" | tail -n 1 | awk '{print $4}' | sed 's/,$//' | tr ',' '.')
        if [ -n "$latest" ]; then
            values="$values$latest\n"
        fi
    fi
done
values=$(echo -e "$values" | head -n $N)
value_count=$(echo "$values" | wc -l)
if [ $value_count -ge $N ]; then
    echo "Debug: Collected $value_count values for N=$N"
    echo "Debug: Values: $values"
    stats=$(echo "$values" | awk '{
        sum += $1
        sumsq += $1*$1
    }
    END {
        mean = sum/NR
        variance = (sumsq/NR) - (mean*mean)
        stddev = sqrt(variance)
        printf "%.6f %.6f", mean, stddev
    }')
    mean=$(echo $stats | awk '{print $1}')
    std_dev=$(echo $stats | awk '{print $2}')
    ELAPSED=$(($(date +%s) - START_TIME))
    echo "Status: ${ELAPSED}s elapsed, Mean: $mean, StdDev: $std_dev"
fi

EXPECTED=$(echo "scale=6; 1.0/$N" | bc -l)

# Monitor until convergence
while true; do
    sleep 10
    # Collect current status
    values=""
    for log in localhost_*_peer4.log; do
        if [ -f "$log" ]; then
            # Get the last "Current value:" line from peer's log
            latest=$(grep "Current value:" "$log" | tail -n 1 | awk '{print $4}' | sed 's/,$//' | tr ',' '.')
            if [ -n "$latest" ]; then
                values="$values$latest\n"
            fi
        fi
    done
    values=$(echo -e "$values" | head -n $N)  # Ensure exactly N values
    value_count=$(echo "$values" | wc -l)
    if [ $value_count -ge $N ]; then
        echo "Debug: Collected $value_count values for N=$N"
        echo "Debug: Values: $values"
        stats=$(echo "$values" | awk '{
            sum += $1
            sumsq += $1*$1
        }
        END {
            mean = sum/NR
            variance = (sumsq/NR) - (mean*mean)
            stddev = sqrt(variance)
            printf "%.6f %.6f", mean, stddev
        }')
        mean=$(echo $stats | awk '{print $1}')
        std_dev=$(echo $stats | awk '{print $2}')
        ELAPSED=$(($(date +%s) - START_TIME))
        echo "Status: ${ELAPSED}s elapsed, Mean: $mean, StdDev: $std_dev"
        
        # Check for convergence
        mean_error=$(echo "scale=6; sqrt(($mean - $EXPECTED) * ($mean - $EXPECTED))" | bc -l)
        if (( $(echo "$std_dev < 0.001 && $mean_error < 0.001" | bc -l) )); then
            echo "Convergence reached! StdDev = $std_dev, MeanError = $mean_error"
            save_results
            exit 0
        fi
    fi
done