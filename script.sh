#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOOT_JAR="$ROOT/soot-4.6.0-jar-with-dependencies.jar"
BUILD_ROOT="$ROOT/.build-script"
RESULTS_DIR="$ROOT/results"
CSV_FILE="$RESULTS_DIR/script_results.csv"
ALGORITHMS=(CHA RTA VTA)
ITERS="3"

if [[ "$#" -gt 1 ]]; then
  echo "Usage: bash script.sh [iterations]" >&2
  exit 1
fi

if [[ "$#" -eq 1 ]]; then
  if [[ "$1" =~ ^[0-9]+$ ]]; then
    ITERS="$1"
  else
    echo "Usage: bash script.sh [iterations]" >&2
    exit 1
  fi
fi

if [[ ! "$ITERS" =~ ^[0-9]+$ ]] || [[ "$ITERS" -lt 1 ]]; then
  echo "Usage: bash script.sh [iterations]" >&2
  exit 1
fi

if [[ ! -f "$SOOT_JAR" ]]; then
  echo "Missing soot jar: $SOOT_JAR" >&2
  exit 1
fi

cd "$ROOT"
mkdir -p "$RESULTS_DIR"

echo "[1/5] Deleting all .class files..."
find "$ROOT" -type f -name '*.class' -delete

echo "[2/5] Compiling analysis drivers..."
rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT"

for algo in "${ALGORITHMS[@]}"; do
  build_dir="$BUILD_ROOT/$algo"
  mkdir -p "$build_dir"
  (
    cd "$ROOT/src"
    javac -cp "$SOOT_JAR" -d "$build_dir" Main.java "$algo/AnalysisTransformer.java"
  )
done

to_ms() {
  awk -v n="$1" 'BEGIN { printf "%.3f", n / 1000000.0 }'
}

speedup() {
  awk -v before="$1" -v after="$2" 'BEGIN { if (after == 0) print "inf"; else printf "%.3f", before / after }'
}

measure_ns() {
  local cp="$1"
  local main_class="$2"
  local scratch_dir="$3"
  shift 3
  local args=("$@")
  local total=0
  local start end i

  for ((i = 1; i <= ITERS; i++)); do
    if [[ -n "$scratch_dir" ]]; then
      rm -rf "$scratch_dir"
    fi

    start="$(date +%s%N)"
    if ! java -Xint -cp "$cp" "$main_class" "${args[@]}" >/dev/null 2>&1; then
      return 1
    fi
    end="$(date +%s%N)"
    total=$((total + end - start))
  done

  echo $((total / ITERS))
}

mapfile -t UNIT_TESTS < <(find "$ROOT/tests" -mindepth 1 -maxdepth 1 -type d -name 'Test*' -printf '%f\n' | sort -V)
if [[ "${#UNIT_TESTS[@]}" -eq 0 ]]; then
  echo "No testcases found under tests/" >&2
  exit 1
fi

TESTS=("${UNIT_TESTS[@]}")

echo "testcase,algorithm,before_ms,after_ms,speedup_after_vs_before" > "$CSV_FILE"

echo "[3/5] Running testcases one by one..."
for t in "${TESTS[@]}"; do
  scratch_dir=""
  baseline_cp="$ROOT"
  test_java="$ROOT/tests/$t/Test.java"
  main_class="tests.$t.Test"
  test_class_rel="tests/$t/Test.class"

  if [[ ! -f "$test_java" ]]; then
    echo "Missing testcase source: $test_java" >&2
    exit 1
  fi

  echo
  echo "===== $t ====="
  javac "$test_java"

  for algo in "${ALGORITHMS[@]}"; do
    build_dir="$BUILD_ROOT/$algo"
    out_dir="$ROOT/sootOutput/$t/$algo"
    log_file="$out_dir/script.log"

    rm -rf "$out_dir"
    mkdir -p "$out_dir"

    echo "Generating $algo output for $t ..."
    if ! java -Xmx8g -cp "$build_dir:$ROOT:$SOOT_JAR" src.Main "$t" "$algo" C > "$log_file" 2>&1; then
      echo "Transformation failed for $t/$algo (see $log_file)" >&2
      exit 1
    fi

    after_cp="$out_dir/after"

    check_before_dir="$out_dir/before"
    check_after_dir="$out_dir/after"
    if [[ ! -f "$check_before_dir/$test_class_rel" ]] || [[ ! -f "$check_after_dir/$test_class_rel" ]]; then
      echo "Missing transformed class files for $t/$algo" >&2
      exit 1
    fi

    if ! before_ns="$(measure_ns "$baseline_cp" "$main_class" "$scratch_dir")"; then
      echo "Measurement failed for $t/$algo (before)" >&2
      exit 1
    fi

    if ! after_ns="$(measure_ns "$after_cp" "$main_class" "$scratch_dir")"; then
      echo "Measurement failed for $t/$algo (after)" >&2
      exit 1
    fi

    before_ms="$(to_ms "$before_ns")"
    after_ms="$(to_ms "$after_ns")"
    after_vs_before="$(speedup "$before_ns" "$after_ns")"

    printf '%s,%s,%s,%s,%s\n' \
      "$t" "$algo" "$before_ms" "$after_ms" "$after_vs_before" >> "$CSV_FILE"

    printf '  %-4s | before=%8sms | after=%8sms | speedup=%s\n' \
      "$algo" "$before_ms" "$after_ms" "$after_vs_before"
  done
done

echo
printf '[4/5] Per-test statistics written to: %s\n' "$CSV_FILE"
echo "[5/5] Done."
