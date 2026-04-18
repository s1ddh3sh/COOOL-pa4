#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
soot_jar="$root/soot-4.6.0-jar-with-dependencies.jar"
algorithms=(CHA RTA VTA)
build_root="$root/.build-algorithms"
output_root="$root/sootOutput"

iters="5"
tests=(Test1 Test2 Test3 Test4 Test5 Test6 Test7 Test8 Test9 Test10)

usage() {
  echo "Usage: ./run_test.sh [iterations] [TestName ...]" >&2
}

if [[ "$#" -ge 1 ]]; then
  if [[ "$1" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]; then
    iters="$1"
    shift
  elif [[ "$1" != Test* ]]; then
    usage
    exit 1
  fi
fi

if [[ "$#" -gt 0 ]]; then
  tests=("$@")
fi

if [[ ! -f "$soot_jar" ]]; then
  echo "Missing: $soot_jar" >&2
  exit 1
fi

to_ms() {
  awk -v n="$1" 'BEGIN { printf "%.3f", n / 1000000.0 }'
}

speedup() {
  awk -v b="$1" -v o="$2" 'BEGIN { if (o == 0) { print "inf" } else { printf "%.3f", b / o } }'
}

measure() {
  local cp="$1"
  local main_class="$2"
  local i start end total=0

  java -Xint -cp "$cp" "$main_class" >/dev/null 2>&1 || return 1

  for ((i = 1; i <= iters; i++)); do
    start="$(date +%s%N)"
    java -Xint -cp "$cp" "$main_class" >/dev/null 2>&1 || return 1
    end="$(date +%s%N)"
    total=$((total + end - start))
  done

  printf '%s\n' $((total / iters))
}

echo "Compiling analysis drivers..."
for algo in "${algorithms[@]}"; do
  build_dir="$build_root/$algo"
  rm -rf "$build_dir"
  mkdir -p "$build_dir"

  javac -cp "$soot_jar" -d "$build_dir" \
    "$root/src/Main.java" \
    "$root/src/$algo/AnalysisTransformer.java"
done

for t in "${tests[@]}"; do
  test_file="$root/tests/$t/Test.java"
  main_class="tests.${t}.Test"
  class_rel="tests/$t/Test.class"

  if [[ ! -f "$test_file" ]]; then
    echo "Unknown test: tests/$t" >&2
    exit 1
  fi

  printf '\n================ %s ================\n' "$t"

  javac "$test_file"

  echo "Generating optimized bytecode..."
  for algo in "${algorithms[@]}"; do
    out_dir="$output_root/$t/$algo"
    log_file="$out_dir/run.log"
    rm -rf "$out_dir"
    mkdir -p "$out_dir"

    java -cp "$build_root/$algo:$soot_jar" src.Main "$t" "$algo" C >"$log_file" 2>&1
  done

  orig_cp="$root"
  cha_cp="$output_root/$t/CHA/after"
  rta_cp="$output_root/$t/RTA/after"
  vta_cp="$output_root/$t/VTA/after"
  cha_before_cp="$output_root/$t/CHA/before"
  rta_before_cp="$output_root/$t/RTA/before"
  vta_before_cp="$output_root/$t/VTA/before"

  for cp in "$orig_cp" "$cha_before_cp" "$rta_before_cp" "$vta_before_cp" "$cha_cp" "$rta_cp" "$vta_cp"; do
    if [[ ! -f "$cp/$class_rel" ]]; then
      echo "Missing $class_rel in $cp" >&2
      exit 1
    fi
  done

  echo "Validating functional equivalence..."
  if ! expected_output="$(java -Xint -cp "$orig_cp" "$main_class" 2>&1)"; then
    echo "Original program failed for $t" >&2
    echo "$expected_output" >&2
    exit 1
  fi

  declare -A valid_after
  declare -A invalid_reason

  for algo in "${algorithms[@]}"; do
    before_cp="$output_root/$t/$algo/before"
    after_cp="$output_root/$t/$algo/after"

    if ! before_output="$(java -Xint -cp "$before_cp" "$main_class" 2>&1)"; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="before crashed"
      continue
    fi
    if [[ "$before_output" != "$expected_output" ]]; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="before output mismatch"
      continue
    fi

    if ! after_output="$(java -Xint -cp "$after_cp" "$main_class" 2>&1)"; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="after crashed"
      continue
    fi
    if [[ "$after_output" != "$expected_output" ]]; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="after output mismatch"
      continue
    fi

    valid_after["$algo"]=1
  done

  echo "Running benchmark ($iters runs each)..."
  orig_ns="$(measure "$orig_cp" "$main_class")"

  orig_ms="$(to_ms "$orig_ns")"

  printf '\n%-10s %-12s %s\n' "Variant" "AvgTime(ms)" "SpeedupVsOriginal"
  printf '%-10s %-12s %s\n' "Original" "$orig_ms" "1.000"

  for algo in "${algorithms[@]}"; do
    after_cp="$output_root/$t/$algo/after"
    if [[ "${valid_after[$algo]}" == "1" ]]; then
      ns="$(measure "$after_cp" "$main_class")"
      ms="$(to_ms "$ns")"
      sp="$(speedup "$orig_ns" "$ns")"
      printf '%-10s %-12s %s\n' "$algo" "$ms" "$sp"
    else
      printf '%-10s %-12s %s\n' "$algo" "INVALID" "${invalid_reason[$algo]}"
    fi
  done

  printf 'Logs: %s\n' "$output_root/$t/{CHA,RTA,VTA}/run.log"
done
