from datetime import datetime
import glob
import os
from matplotlib import pyplot as plt
import numpy as np
import re


def parse_log_file(log_file):
    """
    Parse a single log file to extract timestamps and values.
    Returns list of (timestamp, value) tuples.
    """
    data = []
    last_timestamp = None
    with open(log_file, 'r') as f:
        for line in f:
            # Check for timestamp line (support both Portuguese and English)
            timestamp_match = re.match(r'(\w{3}\.? \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} (?:DA TARDE|DA MANHÃ|PM|AM))', line)
            if timestamp_match:
                timestamp_str = timestamp_match.group(1)
                # Convert Portuguese to English for strptime
                timestamp_str = timestamp_str.replace("dez.", "Dec").replace("jan.", "Jan").replace("fev.", "Feb")
                timestamp_str = timestamp_str.replace("mar.", "Mar").replace("abr.", "Apr").replace("mai.", "May")
                timestamp_str = timestamp_str.replace("jun.", "Jun").replace("jul.", "Jul").replace("ago.", "Aug")
                timestamp_str = timestamp_str.replace("set.", "Sep").replace("out.", "Oct").replace("nov.", "Nov")
                timestamp_str = timestamp_str.replace("DA TARDE", "PM").replace("DA MANHÃ", "AM")
                try:
                    # Parse timestamp
                    last_timestamp = datetime.strptime(timestamp_str, '%b %d, %Y %I:%M:%S %p')
                except ValueError:
                    last_timestamp = None
                continue  # Skip to next line
            
            # Check for value line
            match = re.search(r'Current value:\s*([0-9]+,[0-9]+)', line)
            if match and last_timestamp:
                value_str = match.group(1).replace(',', '.')
                value = float(value_str)
                data.append((last_timestamp, value))
    
    print(f"Parsed {len(data)} data points from {log_file}")
    return data

def plot_individual_peers(log_files, output_dir='peer_plots'):
    """
    Create individual convergence plots for each peer.
    """
    # Get script directory for relative paths
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_path = os.path.join(script_dir, output_dir)
    os.makedirs(output_path, exist_ok=True)
    
    for log_file in log_files:
        # Extract peer ID from filename (e.g., "localhost_10001_peer4.log" -> "Peer 10001")
        peer_id = re.search(r'localhost_(\d+)_peer4\.log', log_file)
        if not peer_id:
            continue
        peer_name = f"Peer {peer_id.group(1)}"
        
        # Parse data
        data = parse_log_file(log_file)
        if not data:
            print(f"Warning: No data found in {log_file}")
            continue
        
        # Extract timestamps and values
        timestamps, values = zip(*data)
        
        # Convert timestamps to seconds since start
        start_time = min(timestamps)
        times_seconds = [(t - start_time).total_seconds() for t in timestamps]
        
        # Create plot
        plt.figure(figsize=(10, 6))
        plt.plot(times_seconds, values, 'b-', linewidth=2, marker='o', markersize=4)
        plt.xlabel('Time (seconds)', fontsize=12)
        plt.ylabel('Value', fontsize=12)
        plt.title(f'{peer_name} Convergence Over Time', fontsize=14, fontweight='bold')
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        
        # Save plot
        output_file = os.path.join(output_dir, f'{peer_name.replace(" ", "_")}_convergence.png')
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        plt.close()
        
        print(f"✓ Saved plot for {peer_name}: {output_file}")

def plot_all_peers_combined(log_files, output_file='all_peers_convergence.png'):
    """
    Create a combined plot showing all peers' convergence.
    """
    plt.figure(figsize=(14, 8))
    
    colors = plt.cm.tab20(np.linspace(0, 1, len(log_files)))  # Use distinct colors
    
    for i, log_file in enumerate(log_files):
        # Extract peer ID
        peer_id = re.search(r'localhost_(\d+)_peer4\.log', log_file)
        if not peer_id:
            continue
        peer_name = f"Peer {peer_id.group(1)}"
        
        # Parse data
        data = parse_log_file(log_file)
        if not data:
            continue
        
        # Extract timestamps and values
        timestamps, values = zip(*data)
        
        # Convert timestamps to seconds since start
        start_time = min(timestamps)
        times_seconds = [(t - start_time).total_seconds() for t in timestamps]
        
        # Plot
        plt.plot(times_seconds, values, color=colors[i], linewidth=2, 
                marker='o', markersize=3, label=peer_name, alpha=0.8)
    
    plt.xlabel('Time (seconds)', fontsize=12)
    plt.ylabel('Value', fontsize=12)
    plt.title('All Peers Convergence Over Time', fontsize=14, fontweight='bold')
    plt.legend(bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=10)
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    
    # Save plot
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    plt.close()
    
    print(f" Saved combined plot: {output_file}")

def main():
    # Find all log files
    log_files = glob.glob('localhost_*_peer4.log')
    
    if not log_files:
        print("Error: No log files found (localhost_*_peer4.log)")
        print("Make sure you're in the directory where Peer4 was run.")
        return
    
    print(f"Found {len(log_files)} log files:")
    for log in log_files:
        print(f"  - {log}")
    
    # Create individual plots
    print("\nGenerating individual peer plots...")
    plot_individual_peers(log_files)
    
    # Create combined plot
    print("\nGenerating combined plot...")
    plot_all_peers_combined(log_files)
    
    print("\n All plots generated successfully!")

if __name__ == "__main__":
    main()