#!/bin/bash

# Configuration
SERVER_HOST="localhost"
SERVER_PORT=9000
CONFIG_FILE="peers.config"
JAVA_CMD="java"
PACKAGE="ds.assignment.tring"
PID_FILE="/tmp/ring_pids.txt"

# Function to start CalculatorServer
start_server() {
    if [ -f $PID_FILE ]; then
        echo "Ring already running. Use 'stop' first."
        return
    fi
    echo "Starting CalculatorServer on $SERVER_HOST:$SERVER_PORT"
    $JAVA_CMD $PACKAGE.CalculatorServer $SERVER_HOST $SERVER_PORT &
    SERVER_PID=$!
    echo "server:$SERVER_PID" > $PID_FILE
    sleep 2
}

# Function to start peers
start_peers() {
    declare -a peer_pids
    position=0
    while IFS=: read -r pos host port; do
        if [[ $pos =~ ^[0-9]+$ ]]; then
            next_pos=$(( (pos + 1) % total_peers ))
            next_host=$(grep "^$next_pos:" $CONFIG_FILE | cut -d: -f2)
            next_port=$(grep "^$next_pos:" $CONFIG_FILE | cut -d: -f3)
            
            echo "Starting Peer $pos on $host:$port (next: $next_host:$next_port)"
            $JAVA_CMD $PACKAGE.Peer $host $port $next_host $next_port $SERVER_HOST $SERVER_PORT $pos $CONFIG_FILE &
            peer_pids[$pos]=$!
            echo "peer$pos:${peer_pids[$pos]}" >> $PID_FILE
            ((position++))
        fi
    done < $CONFIG_FILE
    sleep 5  # Wait for peers to initialize
}

# Function to inject token
inject_token() {
    peer0_host=$(grep "^0:" $CONFIG_FILE | cut -d: -f2)
    peer0_port=$(grep "^0:" $CONFIG_FILE | cut -d: -f3)
    echo "Injecting initial token into Peer 0 ($peer0_host:$peer0_port)"
    $JAVA_CMD $PACKAGE.TokenInjector $peer0_host $peer0_port
}

# Function to kill a specific peer
kill_peer() {
    pos=$1
    if [ -f $PID_FILE ]; then
        pid=$(grep "peer$pos:" $PID_FILE | cut -d: -f2)
        if [ -n "$pid" ] && kill -0 $pid 2>/dev/null; then
            kill $pid
            echo "Killed Peer $pos (PID $pid)"
            sed -i "/peer$pos:/d" $PID_FILE  # Remove from PID file
        else
            echo "Peer $pos not running or already dead"
        fi
    else
        echo "No running ring found."
    fi
}

# Function to restart a specific peer
restart_peer() {
    pos=$1
    if [ -f $PID_FILE ]; then
        host=$(grep "^$pos:" $CONFIG_FILE | cut -d: -f2)
        port=$(grep "^$pos:" $CONFIG_FILE | cut -d: -f3)
        next_pos=$(( (pos + 1) % total_peers ))
        next_host=$(grep "^$next_pos:" $CONFIG_FILE | cut -d: -f2)
        next_port=$(grep "^$next_pos:" $CONFIG_FILE | cut -d: -f3)
        
        echo "Restarting Peer $pos on $host:$port (next: $next_host:$next_port)"
        $JAVA_CMD $PACKAGE.Peer $host $port $next_host $next_port $SERVER_HOST $SERVER_PORT $pos $CONFIG_FILE &
        new_pid=$!
        echo "peer$pos:$new_pid" >> $PID_FILE
        echo "Peer $pos restarted (PID $new_pid)"
    else
        echo "No running ring found."
    fi
}

# Function to show status
show_status() {
    if [ -f $PID_FILE ]; then
        echo "Running processes:"
        while IFS=: read -r type pid; do
            if kill -0 $pid 2>/dev/null; then
                echo "  $type: $pid (running)"
            else
                echo "  $type: $pid (dead)"
            fi
        done < $PID_FILE
    else
        echo "No running ring found."
    fi
}

# Function to stop all
stop_all() {
    if [ -f $PID_FILE ]; then
        echo "Stopping all processes..."
        while IFS=: read -r type pid; do
            if kill -0 $pid 2>/dev/null; then
                kill $pid
                echo "Killed $type (PID $pid)"
            fi
        done < $PID_FILE
        rm $PID_FILE
    else
        echo "No running ring found."
    fi
}

# Count total peers
total_peers=$(grep -c '^[0-9]\+:' $CONFIG_FILE)

# Main logic
case "$1" in
    start)
        start_server
        start_peers
        inject_token
        ;;
    kill)
        if [ -n "$2" ]; then
            kill_peer $2
        else
            echo "Usage: $0 kill <position>"
        fi
        ;;
    restart)
        if [ -n "$2" ]; then
            restart_peer $2
        else
            echo "Usage: $0 restart <position>"
        fi
        ;;
    status)
        show_status
        ;;
    stop)
        stop_all
        ;;
    *)
        echo "Usage: $0 {start|kill <position>|restart <position>|status|stop}"
        echo "  start: Launch server, peers, and inject token"
        echo "  kill <pos>: Kill specific peer (e.g., 2)"
        echo "  restart <pos>: Restart specific peer"
        echo "  status: Show running processes"
        echo "  stop: Kill all processes"
        exit 1
        ;;
esac
