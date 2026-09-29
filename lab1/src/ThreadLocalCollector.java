import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class ThreadLocalCollector implements MetricsCollector {
    private static final int BUCKET_COUNT = 256;

    private static final class ThreadState {
        final AtomicLongArray buckets = new AtomicLongArray(BUCKET_COUNT);
        final AtomicLong count = new AtomicLong();
        final AtomicLong sum = new AtomicLong();
        final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max = new AtomicLong();
    }

    private final List<ThreadState> allStates = new ArrayList<>();
    private final Object listLock = new Object();
    private final ThreadLocal<ThreadState> myState = ThreadLocal.withInitial(() -> {
        ThreadState state = new ThreadState();
        synchronized (listLock) {
            allStates.add(state);
        }
        return state;
    });

    @Override
    public void record(long value) {
        ThreadState state = myState.get();
        int bucket = (int) Math.min(value / 4, BUCKET_COUNT - 1);

        state.buckets.setRelease(bucket, state.buckets.getPlain(bucket) + 1);
        state.count.setRelease(state.count.getPlain() + 1);
        state.sum.setRelease(state.sum.getPlain() + value);

        if (value < state.min.getPlain()) {
            state.min.setRelease(value);
        }
        if (value > state.max.getPlain()) {
            state.max.setRelease(value);
        }
    }

    @Override
    public Snapshot snapshot() {
        List<ThreadState> states;
        synchronized (listLock) {
            states = new ArrayList<>(allStates);
        }

        long[] histogram = new long[BUCKET_COUNT];
        long count = 0;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;

        for (ThreadState state : states) {
            for (int i = 0; i < histogram.length; i++) {
                histogram[i] += state.buckets.get(i);
            }

            long stateCount = state.count.get();
            count += stateCount;
            sum += state.sum.get();
            if (stateCount > 0) {
                min = Math.min(min, state.min.get());
                max = Math.max(max, state.max.get());
            }
        }

        if (count == 0) {
            min = 0;
        }

        long histogramCount = 0;
        for (long bucketCount : histogram) {
            histogramCount += bucketCount;
        }

        long p50 = percentile(histogram, histogramCount, 0.50);
        long p99 = percentile(histogram, histogramCount, 0.99);
        return new Snapshot(histogram, count, sum, min, max, p50, p99);
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
