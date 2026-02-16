#!/bin/bash
# filepath: stop_all.sh

echo "=========================================="
echo "  STOPPING ALL DISTRIBUTED SYSTEMS"
echo "=========================================="
echo ""

# Stop all tmux sessions
echo "1. Stopping tmux sessions..."
tmux kill-session -t peer4_test 2>/dev/null && echo "    P2P session stopped" || echo "   - No P2P session running"
tmux kill-session -t tom_peers 2>/dev/null && echo "    TOM session stopped" || echo "   - No TOM session running"
tmux kill-session -t token_ring 2>/dev/null && echo "    Token Ring session stopped" || echo "   - No Token Ring session running"

echo ""
echo "2. Killing all Java processes..."

# Kill all Java processes for each module (using -9 flag)
pkill -9 -f "ds.assignment.p2p.Peer" 2>/dev/null && echo "    P2P peers killed"
pkill -9 -f "ds.assignment.tom.Peer" 2>/dev/null && echo "    TOM peers killed"
pkill -9 -f "ds.assignment.tring.Peer" 2>/dev/null && echo "    Token Ring peers killed"
pkill -9 -f "ds.assignment.tring.CalculatorServer" 2>/dev/null && echo "    Calculator server killed"
pkill -9 -f "ds.assignment.tring.TokenInjector" 2>/dev/null && echo "    Token injector killed"

# Wait a moment for processes to terminate
sleep 1

# Check if any Java processes related to the assignment are still running
remaining=$(ps aux | grep -E "ds\.assignment\.(p2p|tom|tring)" | grep -v grep | wc -l)

echo ""
if [ "$remaining" -eq 0 ]; then
    echo "   All distributed systems stopped successfully!"
else
    echo "   Warning: $remaining process(es) still running"
    echo "   Run 'ps aux | grep ds.assignment' to see details"
    echo ""
    echo "   Showing remaining processes:"
    ps aux | grep -E "ds\.assignment\.(p2p|tom|tring)" | grep -v grep
fi

echo "=========================================="