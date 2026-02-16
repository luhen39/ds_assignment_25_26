#!/bin/bash

# Tmux script to start all 6 Peer4 instances with specific initial values for network size discovery
# 5 peers at 0.0, 1 peer at 1.0 → converges to 1/6 ≈ 0.166667
# Usage: ./start_p2p.sh

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SESSION_NAME="peer4_test"

# Kill any existing session with the same name
tmux kill-session -t $SESSION_NAME 2>/dev/null

# Create new session with first window for Peer1
tmux new-session -d -s $SESSION_NAME -n "Peer1"

# Window 0: Peer1 (p1) - initial value 0.0
tmux select-pane -t $SESSION_NAME:0
tmux send-keys "cd '$SCRIPT_DIR'" C-m
tmux send-keys "java ds.assignment.p2p.Peer4 localhost 10001 0.0 localhost 10002" C-m

# Window 1: Peer2 (p2) - initial value 0.0
tmux new-window -t $SESSION_NAME:1 -n "Peer2"
tmux send-keys -t $SESSION_NAME:1 "cd '$SCRIPT_DIR'" C-m
tmux send-keys -t $SESSION_NAME:1 "java ds.assignment.p2p.Peer4 localhost 10002 0.0 localhost 10001 localhost 10003 localhost 10004" C-m

# Window 2: Peer3 (p3) - initial value 0.0
tmux new-window -t $SESSION_NAME:2 -n "Peer3"
tmux send-keys -t $SESSION_NAME:2 "cd '$SCRIPT_DIR'" C-m
tmux send-keys -t $SESSION_NAME:2 "java ds.assignment.p2p.Peer4 localhost 10003 0.0 localhost 10002" C-m

# Window 3: Peer4 (p4) - initial value 0.0
tmux new-window -t $SESSION_NAME:3 -n "Peer4"
tmux send-keys -t $SESSION_NAME:3 "cd '$SCRIPT_DIR'" C-m
tmux send-keys -t $SESSION_NAME:3 "java ds.assignment.p2p.Peer4 localhost 10004 0.0 localhost 10002 localhost 10005 localhost 10006" C-m

# Window 4: Peer5 (p5) - initial value 0.0
tmux new-window -t $SESSION_NAME:4 -n "Peer5"
tmux send-keys -t $SESSION_NAME:4 "cd '$SCRIPT_DIR'" C-m
tmux send-keys -t $SESSION_NAME:4 "java ds.assignment.p2p.Peer4 localhost 10005 0.0 localhost 10004" C-m

# Window 5: Peer6 (p6) - initial value 1.0 (the one different peer)
tmux new-window -t $SESSION_NAME:5 -n "Peer6"
tmux send-keys -t $SESSION_NAME:5 "cd '$SCRIPT_DIR'" C-m
tmux send-keys -t $SESSION_NAME:5 "java ds.assignment.p2p.Peer4 localhost 10006 1.0 localhost 10004" C-m

# Attach to the session
tmux attach-session -t $SESSION_NAME
