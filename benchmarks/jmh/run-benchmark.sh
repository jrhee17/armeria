#!/bin/bash
set -euo pipefail

RESULTS_DIR="benchmarks/jmh/build/results/jmh"
AGGREGATE_FILE="$RESULTS_DIR/aggregate-results.txt"
mkdir -p "$RESULTS_DIR"

: > "$AGGREGATE_FILE"

for threads in 10 50 200 500 1000; do
  echo "=== Running with threads=$threads ==="
  ./gradlew :benchmarks:jmh:jmh \
    -Pjmh.includes=HttpsConnectionBenchmark \
    -Pjmh.profilers=gc \
    -Pjmh.iterations=3 \
    -Pjmh.warmupIterations=1 \
    -Pjmh.fork=1 \
    -Pjmh.threads="$threads"

  echo "" >> "$AGGREGATE_FILE"
  echo "=== threads=$threads ===" >> "$AGGREGATE_FILE"
  cat "$RESULTS_DIR/results.txt" >> "$AGGREGATE_FILE"
done

echo ""
echo "=== Aggregated results ==="
cat "$AGGREGATE_FILE"
