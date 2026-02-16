#!/bin/bash

echo "╔═══════╦═══════════════════════════════════════════════════════════╗"
echo "║  #    ║  Peer1  │  Peer2  │  Peer3  │  Peer4  │  Peer5  │  Peer6  ║"
echo "╠═══════╬═══════════════════════════════════════════════════════════╣"

# Extract sequences into arrays
declare -a p1=($(grep "Delivered:" localhost_10001_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))
declare -a p2=($(grep "Delivered:" localhost_10002_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))
declare -a p3=($(grep "Delivered:" localhost_10003_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))
declare -a p4=($(grep "Delivered:" localhost_10004_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))
declare -a p5=($(grep "Delivered:" localhost_10005_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))
declare -a p6=($(grep "Delivered:" localhost_10006_peer.log | awk -F'Delivered: ' '{print $2}' | awk '{print $1}' | head -30))

# Print first 30 words
for i in {0..29}; do
    printf "║ %3d   ║ %-7s │ %-7s │ %-7s │ %-7s │ %-7s │ %-7s ║\n" \
        $((i+1)) "${p1[$i]}" "${p2[$i]}" "${p3[$i]}" "${p4[$i]}" "${p5[$i]}" "${p6[$i]}"
done

echo "╚═══════╩═══════════════════════════════════════════════════════════╝"
echo ""
echo "All columns should be identical if TOM is working correctly!"
