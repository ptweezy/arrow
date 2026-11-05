import arrow.eval.Eval;
import arrow.eval.EvalKt;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Times {@code Eval.now(1).replicate(n).value()} against whichever arrow-eval jar is on the classpath.
 *
 * <pre>
 * java -cp "jars/*" ReplicateBench &lt;variant&gt; time &lt;samples&gt; &lt;warmupMs&gt; &lt;n&gt;...
 * java -cp "jars/*" ReplicateBench &lt;variant&gt; stack &lt;maxN&gt;
 * java -cp "jars/*" ReplicateBench &lt;variant&gt; probe &lt;n&gt;   (exit 0: fits the default stack, 3: overflows)
 * </pre>
 *
 * Every result is one line starting with "RESULT " followed by a JSON object.
 */
public final class ReplicateBench {
  private static volatile Object sink;

  /** Timings run on one thread with this stack, so the old recursive version can reach large n. */
  private static final long BIG_STACK = 1L << 30;

  /** Each sample repeats the call until it lasts at least this long, so short calls are measurable. */
  private static final double MIN_SAMPLE_NS = 10e6;

  private static List<Integer> run(int n) {
    return EvalKt.replicate(Eval.now(1), n).value();
  }

  public static void main(String[] args) throws Exception {
    String variant = args[0];
    String mode = args[1];
    if (mode.equals("probe")) {
      System.exit(fitsDefaultStack(Integer.parseInt(args[2])) ? 0 : PROBE_OVERFLOW);
    }
    emit(env(variant));
    switch (mode) {
      case "time": {
        int samples = Integer.parseInt(args[2]);
        long warmupMs = Long.parseLong(args[3]);
        int[] ns = Arrays.stream(args, 4, args.length).mapToInt(Integer::parseInt).toArray();
        onThread(BIG_STACK, () -> {
          for (int n : ns) emit(measure(variant, n, samples, warmupMs));
        });
        break;
      }
      case "stack":
        stack(variant, Integer.parseInt(args[2]));
        break;
      default:
        throw new IllegalArgumentException("unknown mode " + mode);
    }
  }

  // ---------------------------------------------------------------- timing

  private static Json measure(String variant, int n, int samples, long warmupMs) {
    Json j = base(variant, "time").put("n", n);
    try {
      long f0 = System.nanoTime();
      List<Integer> check = run(n);
      long firstNs = System.nanoTime() - f0;
      if (check.size() != n || (n > 0 && (check.get(0) != 1 || check.get(n - 1) != 1))) {
        return j.put("error", "wrong result, size " + check.size());
      }

      // Warm up for warmupMs, and at least 3 calls.
      int warmOps = 0;
      long w0 = System.nanoTime();
      while (warmOps < 3 || System.nanoTime() - w0 < warmupMs * 1_000_000L) {
        sink = run(n);
        warmOps++;
      }
      double estimate = (System.nanoTime() - w0) / (double) warmOps;
      int reps = (int) Math.max(1, Math.ceil(MIN_SAMPLE_NS / estimate));

      double[] ns = new double[samples];
      double[] bytes = new double[samples];
      long gcCount0 = gcCount(), gcMs0 = gcMs();
      for (int s = 0; s < samples; s++) {
        long a0 = allocated();
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) sink = run(n);
        long t1 = System.nanoTime();
        long a1 = allocated();
        ns[s] = (t1 - t0) / (double) reps;
        bytes[s] = (a1 - a0) / (double) reps;
      }
      long gcCount = gcCount() - gcCount0, gcMs = gcMs() - gcMs0;

      double[] sorted = ns.clone();
      Arrays.sort(sorted);
      double[] sortedBytes = bytes.clone();
      Arrays.sort(sortedBytes);
      return j.put("samples", samples).put("reps", reps).put("warmup_ops", warmOps)
          .put("first_ns", firstNs)
          .put("median_ns", quantile(sorted, 0.5)).put("p25_ns", quantile(sorted, 0.25))
          .put("p75_ns", quantile(sorted, 0.75)).put("min_ns", sorted[0])
          .put("max_ns", sorted[sorted.length - 1]).put("alloc_bytes", quantile(sortedBytes, 0.5))
          .put("gc_count", gcCount).put("gc_ms", gcMs).putRaw("all_ns", Arrays.toString(ns));
    } catch (StackOverflowError e) {
      return j.put("error", "StackOverflowError");
    }
  }

  // ---------------------------------------------------------- stack safety

  private static final int COLD_REPEATS = 3;

  /**
   * Finds the largest n that runs on a thread with the platform's default stack size: cold (every
   * probe in a fresh JVM, so the first call runs mostly interpreted, with bigger frames), then warm
   * (in this JVM after the JIT compiled the path).
   */
  private static void stack(String variant, int maxN) throws Exception {
    long[] coldOk = new long[COLD_REPEATS], coldFail = new long[COLD_REPEATS];
    for (int i = 0; i < COLD_REPEATS; i++) {
      int[] cold = search(maxN, n -> fitsInFreshJvm(variant, n));
      coldOk[i] = cold[0];
      coldFail[i] = cold[1];
    }
    int warmN = (int) Math.max(1000, Math.min(5000, Arrays.stream(coldOk).min().getAsLong() / 2));
    onThread(BIG_STACK, () -> {
      long w0 = System.nanoTime();
      while (System.nanoTime() - w0 < 3_000_000_000L) sink = run(warmN);
    });
    int[] warm = search(maxN, ReplicateBench::fitsDefaultStack);
    emit(base(variant, "stack").put("max_n", maxN)
        .putRaw("cold_largest_ok", Arrays.toString(coldOk))
        .putRaw("cold_first_fail", Arrays.toString(coldFail))
        .put("warm_largest_ok", warm[0]).put("warm_first_fail", warm[1]));
  }

  private interface Probe {
    boolean fits(int n) throws Exception;
  }

  /** Returns {largest n that worked, smallest n that overflowed or -1}, to within 0.5%. */
  private static int[] search(int maxN, Probe probe) throws Exception {
    int ok = 0, fail = -1;
    for (int n = 1000; n <= maxN; n *= 2) {
      if (probe.fits(n)) ok = n;
      else { fail = n; break; }
    }
    if (fail < 0 && ok < maxN) {
      if (probe.fits(maxN)) ok = maxN;
      else fail = maxN;
    }
    if (fail > 0) {
      while (fail - ok > Math.max(1, ok / 200)) {
        int mid = ok + (fail - ok) / 2;
        if (probe.fits(mid)) ok = mid;
        else fail = mid;
      }
    }
    return new int[] {ok, fail};
  }

  /** Runs `probe n` in a new JVM with this JVM's options and classpath. */
  private static boolean fitsInFreshJvm(String variant, int n) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(ReplicateBench.class.getName());
    cmd.add(variant);
    cmd.add("probe");
    cmd.add(Integer.toString(n));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    int code = p.waitFor();
    if (code == 0) return true;
    if (code == PROBE_OVERFLOW) return false;
    throw new IllegalStateException("probe " + n + " exited with " + code);
  }

  private static final int PROBE_OVERFLOW = 3;

  private static boolean fitsDefaultStack(int n) throws Exception {
    boolean[] ok = new boolean[1];
    // stackSize 0 means the platform default (-Xss / ThreadStackSize).
    Thread t = new Thread(null, () -> {
      try {
        ok[0] = run(n).size() == n;
      } catch (StackOverflowError e) {
        ok[0] = false;
      }
    }, "probe-" + n, 0);
    t.start();
    t.join();
    return ok[0];
  }

  // --------------------------------------------------------------- helpers

  private static void onThread(long stackSize, Runnable body) throws Exception {
    Throwable[] failure = new Throwable[1];
    Thread t = new Thread(null, () -> {
      try {
        body.run();
      } catch (Throwable e) {
        failure[0] = e;
      }
    }, "bench", stackSize);
    t.start();
    t.join();
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  private static double quantile(double[] sorted, double q) {
    double pos = q * (sorted.length - 1);
    int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
  }

  private static long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
        .getCurrentThreadAllocatedBytes();
  }

  private static long gcCount() {
    long c = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, gc.getCollectionCount());
    return c;
  }

  private static long gcMs() {
    long c = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, gc.getCollectionTime());
    return c;
  }

  private static Json base(String variant, String kind) {
    return new Json().put("kind", kind).put("variant", variant)
        .put("runner", System.getenv().getOrDefault("BENCH_RUNNER", "local"))
        .put("round", Integer.parseInt(System.getenv().getOrDefault("BENCH_ROUND", "0")))
        .put("java", System.getProperty("java.specification.version"));
  }

  private static Json env(String variant) {
    String stackKb = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .getVMOption("ThreadStackSize").getValue();
    return base(variant, "env")
        .put("java_runtime", System.getProperty("java.runtime.version"))
        .put("java_vm", System.getProperty("java.vm.name"))
        .put("java_vendor", System.getProperty("java.vendor"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        .put("arch", System.getProperty("os.arch"))
        .put("cpus", Runtime.getRuntime().availableProcessors())
        .put("max_heap_mb", Runtime.getRuntime().maxMemory() >> 20)
        .put("thread_stack_kb", stackKb)
        .put("gcs", gcNames())
        .put("cpu", System.getenv().getOrDefault("BENCH_CPU", ""))
        .put("image", System.getenv().getOrDefault("BENCH_IMAGE", ""));
  }

  private static String gcNames() {
    StringBuilder sb = new StringBuilder();
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(gc.getName());
    }
    return sb.toString();
  }

  private static void emit(Json j) {
    System.out.println("RESULT " + j);
    System.out.flush();
  }

  /** Minimal JSON object writer, to keep the harness dependency-free. */
  private static final class Json {
    private final StringBuilder sb = new StringBuilder("{");

    private Json key(String k) {
      if (sb.length() > 1) sb.append(',');
      sb.append('"').append(k).append("\":");
      return this;
    }

    Json put(String k, String v) {
      key(k).sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"").trim()).append('"');
      return this;
    }

    Json put(String k, long v) {
      key(k).sb.append(v);
      return this;
    }

    Json put(String k, double v) {
      key(k).sb.append(String.format(Locale.ROOT, "%.1f", v));
      return this;
    }

    Json putRaw(String k, String rawJson) {
      key(k).sb.append(rawJson);
      return this;
    }

    @Override
    public String toString() {
      return sb + "}";
    }
  }
}
