import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Benchmark {
    private static final int VALUE_COUNT = 1 << 20;
    private static final int SECONDS = 5;
    private static final int MEASUREMENTS = 5;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16};

    public static void main(String[] args) throws InterruptedException {
        String variant = args.length == 0 ? "single" : args[0];
        MetricsCollector collector = switch (variant) {
            case "single" -> new SingleThreadCollector();
            case "locked" -> new SynchronizedCollector();
            case "empty" -> new EmptyLockCollector();
            case "striped" -> new StripedCollector();
            case "threadlocal" -> new ThreadLocalCollector();
            case "doublebuffer" -> new DoubleBufferedCollector();
            default -> throw new IllegalArgumentException("Use single, locked, empty, striped, threadlocal, or doublebuffer");
        };

        int[] values = ZipfGenerator.generate(VALUE_COUNT, 12345L);
        int firstBucket = 0;
        for (int value : values) {
            if (value < 4) {
                firstBucket++;
            }
        }

        System.out.printf("Values: %d; bucket 0: %.1f%%%n",
                values.length, 100.0 * firstBucket / values.length);

        int availableThreads = Runtime.getRuntime().availableProcessors();
        int[] threadCounts = args.length > 1
                ? new int[]{Integer.parseInt(args[1])}
                : THREAD_COUNTS;

        if (args.length > 1 && (threadCounts[0] < 1 || threadCounts[0] > availableThreads
                || (variant.equals("single") && threadCounts[0] != 1))) {
            throw new IllegalArgumentException("Invalid thread count for this variant");
        }

        for (int threadCount : threadCounts) {
            if (threadCount > availableThreads || (variant.equals("single") && threadCount > 1)) {
                continue;
            }

            System.out.printf("%nVariant: %s; threads: %d%n", variant, threadCount);
            double median = measurePoint(collector, values, threadCount);
            System.out.printf(Locale.US, "Result: %s,%d,%.2f%n", variant, threadCount,
                    median / 1_000_000);
        }
    }

    public static double measurePoint(MetricsCollector collector, int[] values, int threadCount)
            throws InterruptedException {
        System.out.println("Warmup: 5 seconds");
        run(collector, values, threadCount, SECONDS);

        double[] results = new double[MEASUREMENTS];
        for (int i = 0; i < MEASUREMENTS; i++) {
            results[i] = run(collector, values, threadCount, SECONDS);
            System.out.printf("Run %d: %.2f million ops/sec%n", i + 1,
                    results[i] / 1_000_000);
        }

        Snapshot snapshot = collector.snapshot();
        long histogramCount = 0;
        for (long bucket : snapshot.buckets()) {
            histogramCount += bucket;
        }
        if (histogramCount != snapshot.count()) {
            throw new IllegalStateException("Histogram does not match count");
        }
        System.out.printf("Recorded count: %d%n", snapshot.count());
        System.out.printf("Snapshot: min=%d ms, max=%d ms, p50=%d ms, p99=%d ms%n",
                snapshot.min(), snapshot.max(), snapshot.p50(), snapshot.p99());

        Arrays.sort(results);
        return results[MEASUREMENTS / 2];
    }

    private static double run(MetricsCollector collector, int[] values, int threadCount, int seconds)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        long[] operations = new long[threadCount];
        Thread[] workers = new Thread[threadCount];

        for (int k = 0; k < threadCount; k++) {
            final int workerIndex = k;
            workers[k] = new Thread(() -> {
                int index = (workerIndex * 1000) % values.length;
                long localCount = 0;

                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (!stop.get()) {
                    collector.record(values[index]);
                    localCount++;
                    index++;
                    if (index == values.length) {
                        index = 0;
                    }
                }

                operations[workerIndex] = localCount;
            });
            workers[k].start();
        }

        long startTime = System.nanoTime();
        start.countDown();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        long endTime = System.nanoTime();

        long totalOperations = 0;
        for (int k = 0; k < threadCount; k++) {
            workers[k].join();
            totalOperations += operations[k];
        }

        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        return totalOperations / elapsedSeconds;
    }
}
