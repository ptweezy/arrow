"""Summarizes ReplicateBench JSON lines (every *.jsonl under the given directory) as Markdown."""

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

CHART_NS = [1000, 10000, 50000, 1000000]


def load(root):
    rows = []
    for path in sorted(Path(root).rglob("*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def quantile(xs, q):
    xs = sorted(xs)
    pos = q * (len(xs) - 1)
    lo, hi = int(pos), min(int(pos) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def ms(ns):
    v = ns / 1e6
    if v >= 100:
        return f"{v:,.0f} ms"
    if v >= 10:
        return f"{v:.1f} ms"
    if v >= 1:
        return f"{v:.2f} ms"
    return f"{v:.3f} ms"


def mb(b):
    if b >= 1 << 30:
        return f"{b / (1 << 30):.2f} GiB"
    if b >= 1 << 20:
        return f"{b / (1 << 20):.1f} MiB"
    return f"{b / 1024:.0f} KiB"


def runner_order(r):
    order = ["ubuntu-latest", "ubuntu-24.04-arm", "windows-latest", "macos-latest"]
    return (order.index(r) if r in order else len(order), r)


def main(root):
    rows = load(root)
    env = {}
    times = defaultdict(list)  # (runner, java, variant, n) -> [row]
    stacks = {}
    errors = []
    for r in rows:
        key = (r["runner"], r["java"])
        if r["kind"] == "env":
            env.setdefault(key, r)
        elif r["kind"] == "time":
            if "error" in r:
                errors.append(r)
            else:
                times[(r["runner"], r["java"], r["variant"], r["n"])].append(r)
        elif r["kind"] == "stack":
            stacks[(r["runner"], r["java"], r["variant"])] = r

    def stat(runner, java, variant, n):
        rs = times.get((runner, java, variant, n))
        if not rs:
            return None
        pooled = [x for r in rs for x in r["all_ns"]]
        return {
            "median": statistics.median(pooled),
            "p25": quantile(pooled, 0.25),
            "p75": quantile(pooled, 0.75),
            "round_medians": [r["median_ns"] for r in rs],
            "alloc": statistics.median(r["alloc_bytes"] for r in rs),
            "samples": len(pooled),
        }

    runners = sorted({k[0] for k in env}, key=runner_order)
    javas = sorted({k[1] for k in env}, key=int)
    out = []
    p = out.append

    p("# Eval.replicate on GitHub-hosted runners\n")
    p("`Eval.now(1).replicate(n).value()`, median of all samples pooled over separate JVMs "
      "(rounds alternate which variant runs first). Timings run on a thread with a 1 GB stack, "
      "as in the PR footnote, so the old version can reach 50,000.\n")

    p("## Runners\n")
    p("| runner | image | CPU | vCPUs | JDKs |")
    p("|---|---|---|---:|---|")
    for runner in runners:
        e = next(env[k] for k in env if k[0] == runner)
        jdks = ", ".join(env[(runner, j)]["java_runtime"] for j in javas if (runner, j) in env)
        p(f"| {runner} | {e['image'] or e['os']} | {e['cpu']} ({e['arch']}) | {e['cpus']} | {jdks} |")
    p("")

    main_java = javas[-1]
    p(f"## The PR table, per runner (Temurin {main_java})\n")
    for runner in runners:
        p(f"**{runner}**\n")
        p("| n | before | after | speedup |")
        p("|---:|---:|---:|---:|")
        for n in CHART_NS:
            b, a = stat(runner, main_java, "before", n), stat(runner, main_java, "after", n)
            if not a and not b:
                continue
            bs = ms(b["median"]) if b else "—"
            as_ = ms(a["median"]) if a else "—"
            sp = f"{b['median'] / a['median']:,.0f}×" if a and b else ""
            p(f"| {n:,} | {bs} | {as_} | {sp} |")
        p("")

    p("## All runners and JDKs\n")
    p("Median, with the spread of per-JVM medians in brackets.\n")
    p("| runner | JDK | before 10k | after 10k | before 50k | after 50k | after 1M | speedup 50k |")
    p("|---|---:|---:|---:|---:|---:|---:|---:|")

    def cell(s):
        if not s:
            return "—"
        lo, hi = min(s["round_medians"]), max(s["round_medians"])
        return f"{ms(s['median'])} [{ms(lo)}–{ms(hi)}]".replace(" ms [", " [").replace(" ms–", "–")

    for runner in runners:
        for java in javas:
            if (runner, java) not in env:
                continue
            g = lambda v, n: stat(runner, java, v, n)
            b50, a50 = g("before", 50000), g("after", 50000)
            sp = f"{b50['median'] / a50['median']:,.0f}×" if b50 and a50 else ""
            p(f"| {runner} | {java} | {cell(g('before', 10000))} | {cell(g('after', 10000))} | "
              f"{cell(b50)} | {cell(a50)} | {cell(g('after', 1000000))} | {sp} |")
    p("")

    p("## Allocation per call\n")
    p("Median bytes allocated by the calling thread, range over every runner and JDK.\n")
    p("| n | before | after |")
    p("|---:|---:|---:|")
    for n in CHART_NS:
        cells = []
        for v in ("before", "after"):
            vals = [stat(r, j, v, n)["alloc"] for r in runners for j in javas if stat(r, j, v, n)]
            if not vals:
                cells.append("—")
            elif max(vals) <= min(vals) * 1.02:
                cells.append(mb(statistics.median(vals)))
            else:
                cells.append(f"{mb(min(vals))} – {mb(max(vals))}")
        p(f"| {n:,} | {cells[0]} | {cells[1]} |")
    p("")

    p("## Default thread stack\n")
    p("Largest n that runs on a thread with the default stack size, by binary search to 0.5%. "
      "Cold: every probe in a fresh JVM, so the first call runs mostly interpreted (bigger "
      "frames); 3 searches, range shown. Warm: in one JVM after 3 s of JIT warm-up.\n")
    p("| runner | JDK | ThreadStackSize | before, cold | before, warm | after, cold and warm |")
    p("|---|---:|---:|---:|---:|---:|")
    for runner in runners:
        for java in javas:
            b, a = stacks.get((runner, java, "before")), stacks.get((runner, java, "after"))
            if not b and not a:
                continue
            kb = env[(runner, java)]["thread_stack_kb"]
            kb = "OS default" if kb == "0" else f"{int(kb):,} KB"

            def lim(oks, fails):
                oks = oks if isinstance(oks, list) else [oks]
                fails = fails if isinstance(fails, list) else [fails]
                if all(f < 0 for f in fails):
                    return f"no overflow ≤ {min(oks):,}"
                lo, hi = min(oks), max(oks)
                return f"{lo:,}" if lo == hi else f"{lo:,}–{hi:,}"

            def cold(s):
                return lim(s["cold_largest_ok"], s["cold_first_fail"]) if s else "—"

            def warm(s):
                return lim(s["warm_largest_ok"], s["warm_first_fail"]) if s else "—"

            after = "—"
            if a:
                after = lim(a["cold_largest_ok"] + [a["warm_largest_ok"]],
                            a["cold_first_fail"] + [a["warm_first_fail"]])
            p(f"| {runner} | {java} | {kb} | {cold(b)} | {warm(b)} | {after} |")
    p("")

    if errors:
        p("## Errors\n")
        for e in errors:
            p(f"- {e['runner']} JDK {e['java']} {e['variant']} n={e['n']}: {e['error']}")
        p("")

    print("\n".join(out))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
