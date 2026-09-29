public class SingleThreadCollector implements MetricsCollector {
    private final long[] buckets = new long[256];
    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max;

    @Override
    public void record(long value) {
        int bucket = (int) Math.min(value / 4, 255);
        buckets[bucket]++;
        count++;
        sum += value;
        min = Math.min(min, value);
        max = Math.max(max, value);
    }

    @Override
    public Snapshot snapshot() {
        long[] copy = buckets.clone();
        long p50 = percentile(copy, count, 0.50);
        long p99 = percentile(copy, count, 0.99);
        long snapshotMin = count == 0 ? 0 : min;

        return new Snapshot(copy, count, sum, snapshotMin, max, p50, p99);
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
