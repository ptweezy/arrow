#!/usr/bin/env bash
# Runs the replicate benchmark for both variants and appends RESULT lines to $1 (JSON lines).
# Expects jars/jars-before/*.jar and jars/jars-after/*.jar (arrow-eval, its runtime classpath
# and replicate-bench.jar).
set -euo pipefail

out="$1"
ROUNDS="${ROUNDS:-3}"
SAMPLES="${SAMPLES:-10}"
WARMUP_MS="${WARMUP_MS:-2000}"
STACK_MAX_N="${STACK_MAX_N:-4000000}"
JARS="${JARS:-jars}"
JVM_OPTS=(-Xms2g -Xmx2g)

cpu_model() {
  case "$(uname -s)" in
    Linux) lscpu | sed -n 's/^Model name: *//p' | head -1 ;;
    Darwin) sysctl -n machdep.cpu.brand_string ;;
    *) powershell -NoProfile -Command "(Get-CimInstance Win32_Processor).Name" | tr -d '\r' ;;
  esac
}

export BENCH_CPU="$(cpu_model || true)"
export BENCH_IMAGE="${ImageOS:-} ${ImageVersion:-}"
export BENCH_RUNNER="${BENCH_RUNNER:-local}"
log="${out%.jsonl}.log"
: > "$out"
: > "$log"

run() {
  local variant="$1"; shift
  echo "::group::$variant $* (round ${BENCH_ROUND:-0})" | tee -a "$log"
  java "${JVM_OPTS[@]}" -cp "$JARS/jars-$variant/*" ReplicateBench "$variant" "$@" \
    | tr -d '\r' | tee -a "$log" | sed -n 's/^RESULT //p' >> "$out"
  echo "::endgroup::"
}

sizes() {
  if [ "$1" = before ]; then echo 1000 10000 50000; else echo 1000 10000 50000 1000000; fi
}

for r in $(seq 1 "$ROUNDS"); do
  export BENCH_ROUND="$r"
  # Alternate which variant goes first, so drift over the job hits both equally.
  if (( r % 2 )); then order="before after"; else order="after before"; fi
  for v in $order; do
    run "$v" time "$SAMPLES" "$WARMUP_MS" $(sizes "$v")
  done
done

export BENCH_ROUND=0
for v in before after; do
  run "$v" stack "$STACK_MAX_N"
done
