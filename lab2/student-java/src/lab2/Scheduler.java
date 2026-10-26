package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/* Этап 1: планировщик, который ведёт настоящие потоки строго по одному (передача хода через семафоры).
 * Этап 2: обнаружение deadlock в главном цикле и корректное завершение застрявших потоков.
 * Внутреннее устройство (свои классы, семафоры, вспомогательные методы для Shared и SchedLock) придумываете сами. */
public final class Scheduler {
    private enum State { NEW, RUNNING, PAUSED, FINISHED }

    private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

    private final class Worker {
        final Scheduler scheduler = Scheduler.this;
        final int id;
        final Runnable body;
        final Semaphore permit = new Semaphore(0);
        Thread thread;
        volatile State state = State.NEW;
        volatile BooleanSupplier waitCondition;
        boolean touchedSincePause = true;
        volatile boolean aborted;
        volatile Throwable failure;

        Worker(int id, Runnable body) {
            this.id = id;
            this.body = body;
        }

        char letter() { return (char) ('A' + id); }

        void start() {
            thread = new Thread(() -> {
                CURRENT.set(this);
                state = State.RUNNING;
                try {
                    body.run();
                } catch (RunAborted ignored) {
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    state = State.FINISHED;
                    schedulerTurn.release();
                    CURRENT.remove();
                }
            }, "lab2-" + letter());
            thread.start();
        }
    }

    private static final class RunAborted extends RuntimeException {
        RunAborted() { super(null, null, false, false); }
    }

    private final List<Worker> workers = new ArrayList<>();
    private final List<SchedLock> locks = new ArrayList<>();
    private final Semaphore schedulerTurn = new Semaphore(0);
    private final StringBuilder trace = new StringBuilder();

    /** Если true, каждая операция с общими данными добавляет строку в log. Выставляет runner. */
    boolean logging;
    /** Лог прогона: строки "<поток>  <операция>", например "A  lock mu", "A  balance -> 100". */
    final List<String> log = new ArrayList<>();
    /** null, если deadlock не было; иначе "held={a:A, b:B}" (этап 2). */
    String deadlock;

    public Scheduler() { }

    /** Зарегистрировать поток. Потоки получают буквы A, B, C… в порядке вызова spawn. */
    public void spawn(Runnable body) {
        if (body == null) throw new NullPointerException("body");
        workers.add(new Worker(workers.size(), body));
    }

    /** Запустить потоки и вести их по одному: на каждой паузе спросить strategy.pick(enabled, last). */
    public void run(Strategy strategy) {
        if (strategy == null) throw new NullPointerException("strategy");

        for (Worker worker : workers) worker.start();

        for (int i = 0; i < workers.size(); i++) schedulerTurn.acquireUninterruptibly();

        int last = -1;
        try {
            while (!allFinished()) {
                Throwable failure = firstFailure();
                if (failure != null) throw propagate(failure);

                List<Integer> enabled = enabledWorkers();
                if (enabled.isEmpty()) {
                    deadlock = heldLocks();
                    abortWorkers();
                    break;
                }

                int chosen = strategy.pick(List.copyOf(enabled), last);
                if (!enabled.contains(chosen)) {
                    throw new IllegalStateException("strategy picked disabled thread " + chosen);
                }

                Worker worker = workers.get(chosen);
                trace.append(worker.letter());
                last = chosen;
                worker.waitCondition = null;
                worker.state = State.RUNNING;
                worker.permit.release();

                schedulerTurn.acquireUninterruptibly();
            }

            Throwable failure = firstFailure();
            if (failure != null) throw propagate(failure);
        } catch (RuntimeException | Error e) {
            abortWorkers();
            throw e;
        } finally {
            joinWorkers();
        }
    }

    /** Трасса прогона: буквы потоков, выбранных во всех решениях подряд, например "AAAABB". */
    public String traceString() {
        return trace.toString();
    }

    /** window() из тестовых программ: пауза перед следующей операцией с общими данными обязательна. */
    public static void point() {
        Worker worker = currentWorker();
        worker.scheduler.addLog(worker, "window");
        worker.touchedSincePause = true;
    }

    /** Ждать, пока cond не станет true. До этого поток не попадает в enabled. */
    public static void await(BooleanSupplier cond) {
        if (cond == null) throw new NullPointerException("cond");
        Worker worker = currentWorker();
        boolean ready = cond.getAsBoolean();
        if (worker.touchedSincePause || !ready) worker.scheduler.pause(worker, cond);
        worker.scheduler.addLog(worker, "await done");
        worker.touchedSincePause = true;
    }

    static void beforeSharedOperation() {
        Worker worker = currentWorker();
        if (worker.touchedSincePause) worker.scheduler.pause(worker, null);
    }

    static void afterSharedOperation(String operation) {
        Worker worker = currentWorker();
        worker.scheduler.addLog(worker, operation);
        worker.touchedSincePause = true;
    }

    static void beforeLockOperation(SchedLock lock) {
        Worker worker = currentWorker();
        if (worker.scheduler != lock.scheduler()) {
            throw new IllegalArgumentException("lock belongs to another scheduler");
        }
        BooleanSupplier available = lock::isFree;
        if (worker.touchedSincePause || !available.getAsBoolean()) {
            worker.scheduler.pause(worker, available);
        }
    }

    static void afterLockOperation(String operation) {
        afterSharedOperation(operation);
    }

    static int currentThreadId() {
        return currentWorker().id;
    }

    void registerLock(SchedLock lock) {
        locks.add(lock);
    }

    private static Worker currentWorker() {
        Worker worker = CURRENT.get();
        if (worker == null) throw new IllegalStateException("scheduler operation outside a spawned thread");
        return worker;
    }

    private void pause(Worker worker, BooleanSupplier waitCondition) {
        worker.waitCondition = waitCondition;
        worker.touchedSincePause = false;
        worker.state = State.PAUSED;
        schedulerTurn.release();
        worker.permit.acquireUninterruptibly();
        if (worker.aborted) throw new RunAborted();
    }

    private void addLog(Worker worker, String operation) {
        if (logging) log.add(worker.letter() + "  " + operation);
    }

    private List<Integer> enabledWorkers() {
        List<Integer> enabled = new ArrayList<>();
        for (Worker worker : workers) {
            if (worker.state != State.PAUSED) continue;
            BooleanSupplier condition = worker.waitCondition;
            if (condition == null || condition.getAsBoolean()) enabled.add(worker.id);
        }
        return enabled;
    }

    private boolean allFinished() {
        for (Worker worker : workers) if (worker.state != State.FINISHED) return false;
        return true;
    }

    private Throwable firstFailure() {
        for (Worker worker : workers) if (worker.failure != null) return worker.failure;
        return null;
    }

    private String heldLocks() {
        StringBuilder result = new StringBuilder("held={");
        boolean first = true;
        for (SchedLock lock : locks) {
            int owner = lock.owner();
            if (owner < 0) continue;
            if (!first) result.append(", ");
            result.append(lock.name).append(':').append((char) ('A' + owner));
            first = false;
        }
        return result.append('}').toString();
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException e) return e;
        if (failure instanceof Error e) throw e;
        return new RuntimeException(failure);
    }

    private void abortWorkers() {
        for (Worker worker : workers) {
            if (worker.state == State.FINISHED) continue;
            worker.aborted = true;
            worker.permit.release();
        }
    }

    private void joinWorkers() {
        boolean interrupted = false;
        for (Worker worker : workers) {
            if (worker.thread == null) continue;
            while (true) {
                try {
                    worker.thread.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
