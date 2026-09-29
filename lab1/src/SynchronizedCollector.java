public class SynchronizedCollector implements MetricsCollector {
    private final SingleThreadCollector collector = new SingleThreadCollector();

    @Override
    public synchronized void record(long value) {
        collector.record(value);
    }

    @Override
    public synchronized Snapshot snapshot() {
        return collector.snapshot();
    }
}
