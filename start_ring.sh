#!/bin/bash

SESSION="token_ring"

# Kill existing session
tmux kill-session -t $SESSION 2>/dev/null

# Create new session
tmux new-session -d -s $SESSION -n "Server"

# Window 0: Server
tmux send-keys -t $SESSION:0 "java ds.assignment.tring.CalculatorServer localhost 9000" C-m

# Window 1: Peer 0
tmux new-window -t $SESSION:1 -n "Peer0"
tmux send-keys -t $SESSION:1 "java ds.assignment.tring.Peer localhost 8081 localhost 8082 localhost 9000 0 peers.config" C-m

# Window 2: Peer 1
tmux new-window -t $SESSION:2 -n "Peer1"
tmux send-keys -t $SESSION:2 "java ds.assignment.tring.Peer localhost 8082 localhost 8083 localhost 9000 1 peers.config" C-m

# Window 3: Peer 2
tmux new-window -t $SESSION:3 -n "Peer2"
tmux send-keys -t $SESSION:3 "java ds.assignment.tring.Peer localhost 8083 localhost 8084 localhost 9000 2 peers.config" C-m

# Window 4: Peer 3
tmux new-window -t $SESSION:4 -n "Peer3"
tmux send-keys -t $SESSION:4 "java ds.assignment.tring.Peer localhost 8084 localhost 8085 localhost 9000 3 peers.config" C-m

# Window 5: Peer 4
tmux new-window -t $SESSION:5 -n "Peer4"
tmux send-keys -t $SESSION:5 "java ds.assignment.tring.Peer localhost 8085 localhost 8081 localhost 9000 4 peers.config" C-m

# Window 6: TokenInjector
tmux new-window -t $SESSION:6 -n "Injector"
tmux send-keys -t $SESSION:6 "sleep 30 && java ds.assignment.tring.TokenInjector localhost 8081" C-m

# Attach to session
tmux attach-session -t $SESSION
