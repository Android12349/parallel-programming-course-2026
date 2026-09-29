import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnapshotStressTest {
    private static final int WRITER_COUNT = 4;
    private static final int SNAPSHOT_COUNT = 10_000;

    public static void main(String[] args) throws InterruptedException {
        String variant = args.length == 0 ? "striped" : args[0];
        MetricsCollector collector = switch (variant) {
            case "locked" -> new SynchronizedCollector();
            case "striped" -> new StripedCollector();
            case "threadlocal" -> new ThreadLocalCollector();
            case "doublebuffer" -> new DoubleBufferedCollector();
            case "doublebuffer-no-recheck" -> new DoubleBufferedCollector(false);
            default -> throw new IllegalArgumentException("Use locked, striped, threadlocal, doublebuffer, or doublebuffer-no-recheck");
        };

        int[] values = ZipfGenerator.generate(1 << 20, 12345L);
        CountDownLatch ready = new CountDownLatch(WRITER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstRecords = new CountDownLatch(WRITER_COUNT);
        AtomicBoolean stop = new AtomicBoolean(false);
        long[] operations = new long[WRITER_COUNT];
        Thread[] writers = new Thread[WRITER_COUNT];

        for (int k = 0; k < WRITER_COUNT; k++) {
            final int writerIndex = k;
            writers[k] = new Thread(() -> {
                int index = (writerIndex * 1000) % values.length;
                long localCount = 0;

                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (!stop.get()) {
                    collector.record(values[index]);
                    localCount++;
                    if (localCount == 1) {
                        firstRecords.countDown();
                    }
                    index++;
                    if (index == values.length) {
                        index = 0;
                    }
                }

                operations[writerIndex] = localCount;
            });
            writers[k].start();
        }

        long less = 0;
        long greater = 0;

        try {
            ready.await();
            start.countDown();
            firstRecords.await();

            for (int i = 0; i < SNAPSHOT_COUNT; i++) {
                Snapshot snapshot = collector.snapshot();
                long histogramCount = 0;
                for (long bucketCount : snapshot.buckets()) {
                    histogramCount += bucketCount;
                }

                if (histogramCount < snapshot.count()) {
                    less++;
                } else if (histogramCount > snapshot.count()) {
                    greater++;
                }
            }
        } finally {
            stop.set(true);
            for (Thread writer : writers) {
                writer.join();
            }
        }

        long totalOperations = 0;
        for (long operationCount : operations) {
            totalOperations += operationCount;
        }

        long finalCount = collector.snapshot().count();
        long bad = less + greater;

        System.out.printf("Variant: %s; writers: %d; snapshots: %d%n",
                variant, WRITER_COUNT, SNAPSHOT_COUNT);
        System.out.printf(Locale.US, "Bad snapshots: %d (%.2f%%)%n",
                bad, 100.0 * bad / SNAPSHOT_COUNT);
        System.out.printf("Histogram < count: %d%n", less);
        System.out.printf("Histogram > count: %d%n", greater);
        System.out.printf("Writer calls: %d%n", totalOperations);
        System.out.printf("Final count: %d%n", finalCount);
        System.out.printf("Final count - writer calls: %d%n", finalCount - totalOperations);
    }
}
