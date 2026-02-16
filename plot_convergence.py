import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import os

# Get script directory for relative paths
script_dir = os.path.dirname(os.path.abspath(__file__))
csv_path = os.path.join(script_dir, 'data', 'convergence_results.csv')

# Change the file check:
if not os.path.exists(csv_path):
    print(f"Error: {csv_path} not found!")
    print("Run build_random_network.sh for different N values and collect data first.")
    exit(1)

# Change the read line:
df = pd.read_csv(csv_path)

# Handle missing or invalid data
df = df.dropna()  # Remove rows with NaN
df = df[df['ConvergenceTime(seconds)'] != 'TIMEOUT']
df = df[df['ConvergenceTime(seconds)'] > 0]  # Remove invalid times

if df.empty:
    print("Error: No valid data found in convergence_results.csv")
    exit(1)

# Convert to numeric
df['ConvergenceTime(seconds)'] = pd.to_numeric(df['ConvergenceTime(seconds)'], errors='coerce')
df['FinalAverage'] = pd.to_numeric(df['FinalAverage'], errors='coerce')
df['ExpectedAverage'] = pd.to_numeric(df['ExpectedAverage'], errors='coerce')
df['StdDev'] = pd.to_numeric(df['StdDev'], errors='coerce')

# Drop any remaining NaN
df = df.dropna()

print("="*50)
print("MANUAL CONVERGENCE ANALYSIS RESULTS")
print("="*50)
print(f"Data points: {len(df)}")
print(df.to_string(index=False))
print("="*50)

# Create figure with two subplots
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

# Plot 1: Convergence Time vs Network Size
ax1.plot(df['N'], df['ConvergenceTime(seconds)'], 'bo-', 
         linewidth=2.5, markersize=10, label='Measured Time')
ax1.set_xlabel('Network Size (N)', fontsize=14, fontweight='bold')
ax1.set_ylabel('Convergence Time (seconds)', fontsize=14, fontweight='bold')
ax1.set_title('Anti-Entropy Gossip: Convergence Time vs Network Size\n(Manual Measurements)', 
              fontsize=15, fontweight='bold')
ax1.grid(True, alpha=0.3, linestyle='--')

# Add logarithmic trend line
if len(df) > 2:
    # Fit log(N) trend
    z = np.polyfit(np.log(df['N']), df['ConvergenceTime(seconds)'], 1)
    p = np.poly1d(z)
    x_trend = np.linspace(df['N'].min(), df['N'].max(), 100)
    ax1.plot(x_trend, p(np.log(x_trend)), 'r--', 
             alpha=0.7, linewidth=2.5, 
             label=f'Trend: T ≈ {z[0]:.1f}·log(N) + {z[1]:.1f}')
    
    # Add O(log N) reference
    ax1.text(0.05, 0.95, 
             'Expected: O(log N) rounds\n' + 
             'Each round ≈ 30s (λ=2/min)\n' +
             'Manual measurements',
             transform=ax1.transAxes, fontsize=11,
             verticalalignment='top',
             bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))

ax1.legend(fontsize=12, loc='upper left')

# Plot 2: Convergence Accuracy
ax2.scatter(df['N'], df['FinalAverage'], s=150, c='blue', 
            marker='o', label='Final Average', zorder=3, alpha=0.7)
ax2.plot(df['N'], df['ExpectedAverage'], 'r--', 
         linewidth=2.5, label='Expected (1/N)', zorder=2)

# Add error bars
errors = abs(df['FinalAverage'] - df['ExpectedAverage'])
ax2.errorbar(df['N'], df['FinalAverage'], yerr=errors, 
             fmt='none', ecolor='gray', alpha=0.5, capsize=5)

ax2.set_xlabel('Network Size (N)', fontsize=14, fontweight='bold')
ax2.set_ylabel('Converged Value', fontsize=14, fontweight='bold')
ax2.set_title('Convergence Accuracy: Final vs Expected Average\n(Manual Measurements)', 
              fontsize=15, fontweight='bold')
ax2.grid(True, alpha=0.3, linestyle='--')
ax2.legend(fontsize=12)

# Add accuracy metrics
max_error = errors.max()
mean_error = errors.mean()
ax2.text(0.05, 0.95, 
         f'Max Error: {max_error:.6f}\n' +
         f'Mean Error: {mean_error:.6f}\n' +
         f'Accuracy: {(1 - mean_error)*100:.4f}%\n' +
         f'Data points: {len(df)}',
         transform=ax2.transAxes, fontsize=11,
         verticalalignment='top',
         bbox=dict(boxstyle='round', facecolor='lightgreen', alpha=0.5))

plt.tight_layout()
plt.savefig('convergence_analysis.png', dpi=300, bbox_inches='tight')
print("\n✓ Plot saved to: convergence_analysis.png")
plt.show()

# Generate summary statistics
print("\n" + "="*50)
print("SUMMARY STATISTICS")
print("="*50)
print(f"Network sizes tested: {df['N'].tolist()}")
print(f"Convergence times (s): {df['ConvergenceTime(seconds)'].tolist()}")
print(f"Average convergence time: {df['ConvergenceTime(seconds)'].mean():.1f}s")
print(f"Fastest convergence (N={df.loc[df['ConvergenceTime(seconds)'].idxmin(), 'N']:.0f}): {df['ConvergenceTime(seconds)'].min():.1f}s")
print(f"Slowest convergence (N={df.loc[df['ConvergenceTime(seconds)'].idxmax(), 'N']:.0f}): {df['ConvergenceTime(seconds)'].max():.1f}s")
print(f"Maximum accuracy error: {max_error:.6f}")
print(f"Mean accuracy error: {mean_error:.6f}")
print("="*50)