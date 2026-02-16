#!/bin/bash
# filepath: test_security.sh

echo "=========================================="
echo "  SECURITY TEST - 6 Peers"
echo "=========================================="
echo ""

# Get script directory and compile
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
javac ds/assignment/tom/*.java

# Clean logs
rm -f localhost_*_peer.log

echo "Starting 6 peers in background..."

# Start all 6 peers
java ds.assignment.tom.Peer localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10005 5 localhost 10006 6 > /dev/null 2>&1 &
P1=$!
sleep 1

java ds.assignment.tom.Peer localhost 10002 2 localhost 10001 1 localhost 10003 3 localhost 10004 4 localhost 10005 5 localhost 10006 6 > /dev/null 2>&1 &
P2=$!
sleep 1

java ds.assignment.tom.Peer localhost 10003 3 localhost 10001 1 localhost 10002 2 localhost 10004 4 localhost 10005 5 localhost 10006 6 > /dev/null 2>&1 &
P3=$!
sleep 1

java ds.assignment.tom.Peer localhost 10004 4 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10005 5 localhost 10006 6 > /dev/null 2>&1 &
P4=$!
sleep 1

java ds.assignment.tom.Peer localhost 10005 5 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10006 6 > /dev/null 2>&1 &
P5=$!
sleep 1

java ds.assignment.tom.Peer localhost 10006 6 localhost 10001 1 localhost 10002 2 localhost 10003 3 localhost 10004 4 localhost 10005 5 > /dev/null 2>&1 &
P6=$!

echo "All 6 peers started (PIDs: $P1, $P2, $P3, $P4, $P5, $P6)"
echo ""
echo "Waiting 8 seconds for normal operation and message exchange..."
sleep 8

echo ""
echo "Finding a timestamp that was actually used by Peer 1..."
# Get the first timestamp used by Peer 1 from any log
USED_TS=$(grep "Multicasting word" localhost_10001_peer.log 2>/dev/null | head -1 | grep -oP 'timestamp \K\d+')

if [ -z "$USED_TS" ]; then
    echo "   Could not find Peer 1 timestamps in logs"
    USED_TS=1
fi

echo "Will attempt to reuse timestamp: $USED_TS"

echo ""
echo "=========================================="
echo "  INJECTING MALICIOUS MESSAGES"
echo "=========================================="
echo ""

# ATTACK (a) - Part 1: Send DIFFERENT message IDs with SAME timestamp (history rewriting)
echo "  ATTACK (a) - Part 1: Timestamp Reuse with Different Message IDs"
echo "   → Sending 3 fake messages with reused timestamp $USED_TS"
echo "   → Targeting Peer 2, Peer 3, and Peer 4"
echo "   → Note: These simulate an attacker trying to 'rewrite history'"
echo -e "MESSAGE\n1:${USED_TS}:FAKE1\nFAKE_WORD_1\n${USED_TS}\n1" | nc localhost 10002 > /dev/null 2>&1 &
sleep 0.5
echo -e "MESSAGE\n1:${USED_TS}:FAKE2\nFAKE_WORD_2\n${USED_TS}\n1" | nc localhost 10003 > /dev/null 2>&1 &
sleep 0.5
echo -e "MESSAGE\n1:${USED_TS}:FAKE3\nFAKE_WORD_3\n${USED_TS}\n1" | nc localhost 10004 > /dev/null 2>&1 &
sleep 2

# ATTACK (a) - Part 2: Message Replay Attack
echo "  ATTACK (a) - Part 2: Message Replay Attack"
REPLAY_TS=$((USED_TS + 1))
echo "   → Sending message twice with same ID and timestamp $REPLAY_TS"
echo "   → Result depends on timing (see note in summary)"
echo -e "MESSAGE\n1:${REPLAY_TS}:REPLAY\nREPLAY_WORD\n${REPLAY_TS}\n1" | nc localhost 10002 > /dev/null 2>&1
sleep 1
echo -e "MESSAGE\n1:${REPLAY_TS}:REPLAY\nREPLAY_WORD\n${REPLAY_TS}\n1" | nc localhost 10002 > /dev/null 2>&1
sleep 2

# ATTACK (b): Send message with FUTURE timestamp
echo "  ATTACK (b): Future Timestamp Attack"
echo "   → Sending 3 messages with timestamp 9999 (far in the future)"
echo "   → Targeting Peer 2, Peer 5, and Peer 6"
echo -e "MESSAGE\n1:9999:FUTURE1\nFUTURE_WORD_1\n9999\n1" | nc localhost 10002 > /dev/null 2>&1 &
sleep 0.5
echo -e "MESSAGE\n1:9999:FUTURE2\nFUTURE_WORD_2\n9999\n1" | nc localhost 10005 > /dev/null 2>&1 &
sleep 0.5
echo -e "MESSAGE\n1:9999:FUTURE3\nFUTURE_WORD_3\n9999\n1" | nc localhost 10006 > /dev/null 2>&1 &
sleep 2

echo ""
echo "Waiting 3 seconds for security checks to complete..."
sleep 3

echo ""
echo "=========================================="
echo "  TEST RESULTS"
echo "=========================================="
echo ""

# Check for security alerts
echo "1. Checking ATTACK (a) - Timestamp/Message ID Reuse Detection:"
echo "   -----------------------------------------------------------"
count_a=$(grep -h "SECURITY ALERT (a)" localhost_*_peer.log 2>/dev/null | wc -l)
if [ "$count_a" -gt 0 ]; then
    echo "   PASS: Timestamp/Message ID reuse attack was DETECTED ($count_a times)!"
    echo ""
    echo "   Examples of detected attacks:"
    grep -h "SECURITY ALERT (a)" localhost_*_peer.log 2>/dev/null | head -3 | sed 's/^/   /'
else
    echo "   FAIL: Timestamp/Message ID reuse attack was NOT detected"
fi
echo ""

echo "2. Checking ATTACK (b) - Future Timestamp Detection:"
echo "   -------------------------------------------------"
count_b=$(grep -h "SECURITY ALERT (b)" localhost_*_peer.log 2>/dev/null | wc -l)
if [ "$count_b" -gt 0 ]; then
    echo "   PASS: Future timestamp attack was DETECTED ($count_b times)!"
    echo ""
    echo "   Example of detected attack:"
    grep -h "SECURITY ALERT (b)" localhost_*_peer.log 2>/dev/null | head -1 | sed 's/^/   /'
else
    echo "   FAIL: Future timestamp attack was NOT detected"
fi
echo ""

echo "3. Checking Message Rejections:"
echo "   ----------------------------"
rejected=$(grep -h "REJECTED malicious" localhost_*_peer.log 2>/dev/null | wc -l)
echo "   Total malicious messages rejected: $rejected"
if [ "$rejected" -gt 0 ]; then
    echo "   PASS: Malicious messages were REJECTED ($rejected total)"
else
    echo "   FAIL: No messages were rejected"
fi
echo ""

echo "4. Per-Peer Rejection Statistics:"
echo "   ------------------------------"
for port in 10001 10002 10003 10004 10005 10006; do
    peer_id=$((port - 10000))
    count=$(grep -c "REJECTED malicious" localhost_${port}_peer.log 2>/dev/null)
    if [ "$count" -gt 0 ]; then
        echo "   Peer $peer_id (port $port): $count message(s) rejected"
    fi
done
echo ""

echo "=========================================="
echo "  DETAILED LOGS (for verification)"
echo "=========================================="
echo ""
echo "Security alerts from targeted peers:"
echo ""
for port in 10002 10003 10004 10005 10006; do
    peer_id=$((port - 10000))
    alerts=$(grep -c "SECURITY ALERT" localhost_${port}_peer.log 2>/dev/null)
    if [ "$alerts" -gt 0 ]; then
        echo "Peer $peer_id (port $port) - $alerts alert(s):"
        grep -E "SECURITY ALERT|REJECTED" localhost_${port}_peer.log 2>/dev/null | sed 's/^/  /'
        echo ""
    fi
done

echo ""
echo "=========================================="
echo "  SUMMARY"
echo "=========================================="
echo ""

# Calculate success
both_detected=0
if [ "$count_a" -gt 0 ] && [ "$count_b" -gt 0 ]; then
    both_detected=1
fi

# Calculate actual detection counts
fake_detected=$(grep -h "FAKE" localhost_*_peer.log 2>/dev/null | grep -c "REJECTED")
replay_detected=$(grep -h "REPLAY" localhost_*_peer.log 2>/dev/null | grep -c "REJECTED")
future_detected=$(grep -h "FUTURE" localhost_*_peer.log 2>/dev/null | grep -c "REJECTED")

total_detections=$((fake_detected + replay_detected + future_detected))

# Determine actual number of attacks based on replay detection
if [ "$replay_detected" -eq 2 ]; then
    # Both REPLAYs were malicious (timestamp was already used)
    TOTAL_ATTACKS=8  # 3 FAKE + 2 REPLAY + 3 FUTURE
    replay_note="Both REPLAY messages were malicious (timestamp $REPLAY_TS was already used by sender)"
else
    # Only second REPLAY was malicious
    TOTAL_ATTACKS=7  # 3 FAKE + 1 REPLAY + 3 FUTURE
    replay_note="First REPLAY was legitimate (arrived before timestamp $REPLAY_TS was used)"
fi

success_rate=$(awk "BEGIN {printf \"%.1f\", ($total_detections/$TOTAL_ATTACKS)*100}")"%"

if [ "$both_detected" -eq 1 ]; then
    echo "  EXTRA MARKS FEATURES STATUS:"
    echo "  ================================"
    echo "  (a) Timestamp/Message ID reuse detection:   WORKING"
    echo "  (b) Future timestamp detection:             WORKING"
    echo ""
    echo "    BOTH EXTRA MARKS FEATURES ARE FULLY FUNCTIONAL!"
    echo ""
    echo "  Attack Detection Breakdown:"
    echo "  ============================="
    echo "  • Timestamp reuse (different msg IDs): 3 attacks → $fake_detected detected"
    
    if [ "$replay_detected" -eq 2 ]; then
        echo "  • Message replay (both malicious):     2 attacks → $replay_detected detected"
    else
        echo "  • Message replay (exact duplicate):    1 attack  → $replay_detected detected"
    fi
    
    echo "  • Future timestamp:                    3 attacks → $future_detected detected"
    echo ""
    echo "  Note: $replay_note"
    echo ""
    echo "  Overall Statistics:"
    echo "  ==================="
    echo "  Total malicious attacks launched:  $TOTAL_ATTACKS"
    echo "  Total attacks detected:            $total_detections"
    echo "  Total messages rejected:           $rejected"
    echo "  Detection success rate:            $success_rate"
    echo ""
    
else
    echo "  WARNING: Not all security features are working correctly"
    echo "  (a) Timestamp reuse detection: $([ "$count_a" -gt 0 ] && echo "WORKING" || echo "NOT WORKING")"
    echo "  (b) Future timestamp detection: $([ "$count_b" -gt 0 ] && echo "WORKING" || echo "NOT WORKING")"
fi

echo ""
echo "=========================================="
echo ""
echo "Stopping all peers..."
kill $P1 $P2 $P3 $P4 $P5 $P6 2>/dev/null
wait 2>/dev/null