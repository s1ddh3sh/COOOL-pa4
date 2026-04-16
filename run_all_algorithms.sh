#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
soot_jar="$root/soot-4.6.0-jar-with-dependencies.jar"
test_name="${1:-Test1}"

if [[ "$#" -gt 1 ]]; then
  echo "Usage: ./run_all_algorithms.sh [TestName]" >&2
  exit 1
fi

algorithms=(CHA RTA VTA)
build_root="$root/.build-algorithms/$test_name"
output_root="$root/sootOutput/$test_name"

if [[ ! -f "$soot_jar" ]]; then
  echo "Missing: $soot_jar" >&2
  exit 1
fi

if [[ ! -d "$root/tests/$test_name" ]]; then
  echo "Unknown test: tests/$test_name" >&2
  exit 1
fi

javac "$root/tests/$test_name/Test.java"

mkdir -p "$build_root" "$output_root"

echo "Generating optimized bytecode for test: $test_name"

for algo in "${algorithms[@]}"; do
  build_dir="$build_root/$algo"
  out_dir="$output_root/$algo"

  rm -rf "$build_dir" "$out_dir"
  mkdir -p "$build_dir" "$out_dir"

  javac -cp "$soot_jar" -d "$build_dir" \
    "$root/src/Main.java" \
    "$root/src/$algo/AnalysisTransformer.java"

  echo "  -> $algo"
  java -cp "$build_dir:$soot_jar" src.Main "$test_name" "$algo" C >"$out_dir/run.log"

  echo "     output: sootOutput/$test_name/$algo/{before,after}"
done

echo "Done. Use the README commands to run and compare baseline vs optimized classes."
