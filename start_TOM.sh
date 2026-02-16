#!/bin/bash

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SESSION="tom_peers"

# Kill existing session
tmux kill-session -t $SESSION 2>/dev/null

# Create new session
tmux new-session -d -s $SESSION -n "Peer1"

# Window 0: Peer 1 (receives peers 2-6)
tmux send-keys -t $SESSION:0 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10005 5 localhost 10006 6" C-m

# Window 1: Peer 2 (receives peers 1,3-6)
tmux new-window -t $SESSION:1 -n "Peer2"
tmux send-keys -t $SESSION:1 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10002 2 localhost 10001 1 localhost 10003 3 localhost 10004 4 localhost 10005 5 localhost 10006 6" C-m

# Window 2: Peer 3 (receives peers 1-2,4-6)
tmux new-window -t $SESSION:2 -n "Peer3"
tmux send-keys -t $SESSION:2 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10003 3 localhost 10001 1 localhost 10002 2 localhost 10004 4 localhost 10005 5 localhost 10006 6" C-m

# Window 3: Peer 4 (receives peers 1-3,5-6)
tmux new-window -t $SESSION:3 -n "Peer4"
tmux send-keys -t $SESSION:3 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10004 4 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10005 5 localhost 10006 6" C-m

# Window 4: Peer 5 (receives peers 1-4,6)
tmux new-window -t $SESSION:4 -n "Peer5"
tmux send-keys -t $SESSION:4 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10005 5 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10006 6" C-m

# Window 5: Peer 6 (receives peers 1-5)
tmux new-window -t $SESSION:5 -n "Peer6"
tmux send-keys -t $SESSION:5 "cd '$SCRIPT_DIR' && java ds.assignment.tom.Peer localhost 10006 6 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10005 5" C-m

# Select first window
tmux select-window -t $SESSION:0

# Attach to session
tmux attach-session -t $SESSION