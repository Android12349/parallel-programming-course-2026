import java.util.concurrent.atomic.AtomicLong;

public class StripedCollector implements MetricsCollector {
    private static final int STRIPE_COUNT = 16;

    private final long[] buckets = new long[256];
    private final Object[] locks = new Object[STRIPE_COUNT];
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sum = new AtomicLong();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong();

    public StripedCollector() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    @Override
    public void record(long value) {
        int bucket = (int) Math.min(value / 4, 255);
        int stripe = bucket % STRIPE_COUNT;

        synchronized (locks[stripe]) {
            buckets[bucket]++;
        }

        count.incrementAndGet();
        sum.addAndGet(value);

        long currentMin = min.get();
        while (value < currentMin && !min.compareAndSet(currentMin, value)) {
            currentMin = min.get();
        }

        long currentMax = max.get();
        while (value > currentMax && !max.compareAndSet(currentMax, value)) {
            currentMax = max.get();
        }
    }

    @Override
    public Snapshot snapshot() {
        long[] copy = new long[buckets.length];

        for (int stripe = 0; stripe < STRIPE_COUNT; stripe++) {
            synchronized (locks[stripe]) {
                for (int bucket = stripe; bucket < buckets.length; bucket += STRIPE_COUNT) {
                    copy[bucket] = buckets[bucket];
                }
            }
        }

        long snapshotCount = count.get();
        long snapshotSum = sum.get();
        long snapshotMin = snapshotCount == 0 ? 0 : min.get();
        long snapshotMax = max.get();

        long histogramCount = 0;
        for (long bucketCount : copy) {
            histogramCount += bucketCount;
        }

        long p50 = percentile(copy, histogramCount, 0.50);
        long p99 = percentile(copy, histogramCount, 0.99);
        return new Snapshot(copy, snapshotCount, snapshotSum, snapshotMin, snapshotMax, p50, p99);
    }

    private long percentile(long[] histogram, long total, double fraction) {
        if (total == 0) {
            return 0;
        }

        double threshold = total * fraction;
        long accumulated = 0;

        for (int i = 0; i < histogram.length; i++) {
            accumulated += histogram[i];
            if (accumulated >= threshold) {
                return i * 4L;
            }
        }

        throw new IllegalStateException("Histogram does not match count");
    }
}
