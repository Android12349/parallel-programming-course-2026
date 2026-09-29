import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DoubleBufferedCollector implements MetricsCollector {
    private static final int BUCKET_COUNT = 256;
    private static final int NOWHERE = -1;

    private static final class ThreadBuffers {
        final long[][] buckets = new long[2][BUCKET_COUNT];
        final long[] count = new long[2];
        final long[] sum = new long[2];
        final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] max = new long[2];
        final AtomicInteger inside = new AtomicInteger(NOWHERE);
    }

    private final Object snapshotLock = new Object();
    private final List<ThreadBuffers> allBuffers = new ArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private final boolean recheckActive;
    private final ThreadLocal<ThreadBuffers> myBuffers = ThreadLocal.withInitial(() -> {
        ThreadBuffers buffers = new ThreadBuffers();
        synchronized (snapshotLock) {
            allBuffers.add(buffers);
        }
        return buffers;
    });

    private final long[] totalBuckets = new long[BUCKET_COUNT];
    private long totalCount;
    private long totalSum;
    private long totalMin = Long.MAX_VALUE;
    private long totalMax;

    public DoubleBufferedCollector() {
        this(true);
    }

    public DoubleBufferedCollector(boolean recheckActive) {
        this.recheckActive = recheckActive;
    }

    @Override
    public void record(long value) {
        ThreadBuffers buffers = myBuffers.get();
        int buffer;

        while (true) {
            buffer = active.get();
            buffers.inside.set(buffer);
            if (!recheckActive || active.get() == buffer) {
                break;
            }
            buffers.inside.setRelease(NOWHERE);
        }

        int bucket = (int) Math.min(value / 4, BUCKET_COUNT - 1);
        buffers.buckets[buffer][bucket]++;
        buffers.count[buffer]++;
        buffers.sum[buffer] += value;
        if (value < buffers.min[buffer]) {
            buffers.min[buffer] = value;
        }
        if (value > buffers.max[buffer]) {
            buffers.max[buffer] = value;
        }
        buffers.inside.setRelease(NOWHERE);
    }

    @Override
    public Snapshot snapshot() {
        synchronized (snapshotLock) {
            int old = active.get();
            active.set(1 - old);

            for (ThreadBuffers buffers : allBuffers) {
                while (buffers.inside.get() == old) {
                    Thread.onSpinWait();
                }

                for (int i = 0; i < BUCKET_COUNT; i++) {
                    totalBuckets[i] += buffers.buckets[old][i];
                }
                totalCount += buffers.count[old];
                totalSum += buffers.sum[old];
                if (buffers.count[old] > 0) {
                    totalMin = Math.min(totalMin, buffers.min[old]);
                    totalMax = Math.max(totalMax, buffers.max[old]);
                }

                Arrays.fill(buffers.buckets[old], 0);
                buffers.count[old] = 0;
                buffers.sum[old] = 0;
                buffers.min[old] = Long.MAX_VALUE;
                buffers.max[old] = 0;
            }

            long min = totalCount == 0 ? 0 : totalMin;
            long p50 = percentile(totalBuckets, totalCount, 0.50);
            long p99 = percentile(totalBuckets, totalCount, 0.99);
            return new Snapshot(totalBuckets.clone(), totalCount, totalSum, min, totalMax, p50, p99);
        }
    }

    private long percentile(long[] histogram, long count, double fraction) {
        if (count == 0) {
            return 0;
        }

        double threshold = count * fraction;
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
