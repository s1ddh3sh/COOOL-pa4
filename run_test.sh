#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
soot_jar="$root/soot-4.6.0-jar-with-dependencies.jar"
algorithms=(CHA RTA VTA)
build_root="$root/.build-algorithms"
output_root="$root/sootOutput"
csv_file="$root/run_test.csv"

iters="10"
tests=(Test1 Test2 Test3 Test4 Test5 Test6 Test7 Test8 Test9 Test10 out-fop out-avrora out-batik out-luindex out-xalan)

usage() {
  echo "Usage: ./run_test.sh [iterations] [TestName ...]" >&2
}

if [[ "$#" -ge 1 ]]; then
  if [[ "$1" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]; then
    iters="$1"
    shift
  elif [[ "$1" != Test* && "$1" != out-* ]]; then
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

printf '%s\n' 'testcase,baseline,cha,rta,vta,speedup_cha,speedup_rta,speedup_vta' > "$csv_file"

to_ms() {
  awk -v n="$1" 'BEGIN { printf "%.3f", n / 1000000.0 }'
}

speedup() {
  awk -v b="$1" -v o="$2" 'BEGIN { if (o == 0) { print "inf" } else { printf "%.3f", b / o } }'
}

measure() {
  local cp="$1"
  local main_class="$2"
  local scratch_dir="$3"
  shift 3
  local args=("$@")
  local i start end total=0

  # Warm up and verify success
  if [[ -n "$scratch_dir" ]]; then rm -rf "$scratch_dir"; fi
  if ! output="$(java -Xint -cp "$cp" "$main_class" "${args[@]}" 2>&1)"; then
    echo "Measurement failed (warm-up): $output" >&2
    return 1
  fi

  for ((i = 1; i <= iters; i++)); do
    if [[ -n "$scratch_dir" ]]; then rm -rf "$scratch_dir"; fi
    start="$(date +%s%N)"
    if ! output="$(java -Xint -cp "$cp" "$main_class" "${args[@]}" 2>&1)"; then
      echo "Measurement failed (iteration $i): $output" >&2
      return 1
    fi
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
  scratch_dir="$root/.tmp_scratch"
  if [[ -d "$root/decapo/$t" ]]; then
    is_dacapo=1
    main_class="Harness"
    class_rel="Harness.class"
    orig_cp_base="$root/decapo/$t"
    bench_id="${t#out-}"
    # Use isolated scratch directory for DaCapo
    extra_args=("-s" "small" "--scratch-directory" "$scratch_dir" "$bench_id")
    measure_scratch="$scratch_dir"
  else
    is_dacapo=0
    test_file="$root/tests/$t/Test.java"
    main_class="tests.${t}.Test"
    class_rel="tests/$t/Test.class"
    orig_cp_base="$root"
    extra_args=()
    measure_scratch=""

    if [[ ! -f "$test_file" ]]; then
      echo "Unknown test: $t" >&2
      exit 1
    fi
  fi

  printf '\n================ %s ================\n' "$t"

  if [[ "$is_dacapo" -eq 0 ]]; then
    javac "$test_file"
  fi

  echo "Generating optimized bytecode..."
  for algo in "${algorithms[@]}"; do
    out_dir="$output_root/$t/$algo"
    log_file="$out_dir/run.log"
    rm -rf "$out_dir"
    mkdir -p "$out_dir"

    java -Xmx8g -cp "$root:$build_root/$algo:$soot_jar" src.Main "$t" "$algo" C >"$log_file" 2>&1
  done

  orig_cp="$orig_cp_base"
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

  dacapo_jar="$root/decapo/dacapo-9.12-MR1-bach.jar"
  if [[ "$is_dacapo" -eq 1 ]]; then
    if [[ ! -f "$dacapo_jar" ]]; then
      echo "ensure decapo/ folder and it has testcases along with decapo .jar" >&2
      exit 1
    fi
    orig_cp="$orig_cp:$dacapo_jar"
    cha_cp="$cha_cp:$dacapo_jar"
    rta_cp="$rta_cp:$dacapo_jar"
    vta_cp="$vta_cp:$dacapo_jar"
    cha_before_cp="$cha_before_cp:$dacapo_jar"
    rta_before_cp="$rta_before_cp:$dacapo_jar"
    vta_before_cp="$vta_before_cp:$dacapo_jar"
  fi

  echo "Validating functional equivalence..."
  rm -rf "$scratch_dir"
  if ! expected_output="$(java -Xint -cp "$orig_cp" "$main_class" "${extra_args[@]}" 2>&1)"; then
    echo "Original program failed for $t" >&2
    echo "$expected_output" >&2
    exit 1
  fi

  declare -A valid_after
  declare -A invalid_reason

  for algo in "${algorithms[@]}"; do
    before_cp="$output_root/$t/$algo/before"
    after_cp="$output_root/$t/$algo/after"
    if [[ "$is_dacapo" -eq 1 ]]; then
      before_cp="$before_cp:$dacapo_jar"
      after_cp="$after_cp:$dacapo_jar"
    fi

    rm -rf "$scratch_dir"
    if ! before_output="$(java -Xint -cp "$before_cp" "$main_class" "${extra_args[@]}" 2>&1)"; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="before crashed"
      continue
    fi
    if [[ "$is_dacapo" -eq 0 && "$before_output" != "$expected_output" ]]; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="before output mismatch"
      continue
    fi

    rm -rf "$scratch_dir"
    if ! after_output="$(java -Xint -cp "$after_cp" "$main_class" "${extra_args[@]}" 2>&1)"; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="after crashed"
      continue
    fi
    if [[ "$is_dacapo" -eq 0 && "$after_output" != "$expected_output" ]]; then
      valid_after["$algo"]=0
      invalid_reason["$algo"]="after output mismatch"
      continue
    fi

    valid_after["$algo"]=1
  done

  echo "Running benchmark ($iters runs each)..."
  if ! orig_ns="$(measure "$orig_cp" "$main_class" "$measure_scratch" "${extra_args[@]}")"; then
    echo "Failed to measure original program for $t" >&2
    continue
  fi

  orig_ms="$(to_ms "$orig_ns")"

  declare -A after_ms
  declare -A after_sp

  for algo in "${algorithms[@]}"; do
    after_cp="$output_root/$t/$algo/after"
    if [[ "$is_dacapo" -eq 1 ]]; then
      after_cp="$after_cp:$dacapo_jar"
    fi

    if [[ "${valid_after[$algo]}" == "1" ]]; then
      if ns="$(measure "$after_cp" "$main_class" "$measure_scratch" "${extra_args[@]}")"; then
        after_ms["$algo"]="$(to_ms "$ns")"
        after_sp["$algo"]="$(speedup "$orig_ns" "$ns")"
      else
        after_ms["$algo"]="INVALID"
        after_sp["$algo"]="INVALID"
      fi
    else
      after_ms["$algo"]="INVALID"
      after_sp["$algo"]="INVALID"
    fi
  done

  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$t" \
    "$orig_ms" \
    "${after_ms[CHA]}" "${after_ms[RTA]}" "${after_ms[VTA]}" \
    "${after_sp[CHA]}" "${after_sp[RTA]}" "${after_sp[VTA]}" >> "$csv_file"

  printf '\n%-10s %-12s %s\n' "Variant" "AvgTime(ms)" "SpeedupVsOriginal"
  printf '%-10s %-12s %s\n' "Original" "$orig_ms" "1.000"

  for algo in "${algorithms[@]}"; do
    if [[ "${valid_after[$algo]}" == "1" && "${after_ms[$algo]}" != "INVALID" ]]; then
      printf '%-10s %-12s %s\n' "$algo" "${after_ms[$algo]}" "${after_sp[$algo]}"
    else
      printf '%-10s %-12s %s\n' "$algo" "INVALID" "${invalid_reason[$algo]}"
    fi
  done

  printf 'Logs: %s\n' "$output_root/$t/{CHA,RTA,VTA}/run.log"
done
