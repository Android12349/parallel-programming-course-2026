package lab2;

/* Этап 2: лок, о котором знает планировщик. Поток, ждущий занятый лок, не попадает в enabled;
 * обычный synchronized / ReentrantLock здесь нельзя — поток уснёт с ходом в руках и всё зависнет.
 * Лог: "lock <имя>", "unlock <имя>". Планировщик должен знать все локи, чтобы печатать held={a:A, b:B}. */
public final class SchedLock {
    private final Scheduler scheduler;
    final String name;
    private int owner = -1;

    /** Создаётся в setup() тестовой программы; зарегистрируйте лок в планировщике s. */
    public SchedLock(Scheduler s, String name) {
        if (s == null) throw new NullPointerException("scheduler");
        if (name == null) throw new NullPointerException("name");
        this.scheduler = s;
        this.name = name;
        s.registerLock(this);
    }

    /** Пауза; если лок занят — ждать, не участвуя в выборе; затем стать владельцем. */
    public void lock() {
        Scheduler.beforeLockOperation(this);
        owner = Scheduler.currentThreadId();
        Scheduler.afterLockOperation("lock " + name);
    }

    /** Пауза; освободить лок, ждавшие потоки снова могут быть выбраны. */
    public void unlock() {
        Scheduler.beforeSharedOperation();
        int current = Scheduler.currentThreadId();
        if (owner != current) throw new IllegalMonitorStateException("thread does not own lock " + name);
        owner = -1;
        Scheduler.afterLockOperation("unlock " + name);
    }

    boolean isFree() { return owner == -1; }

    int owner() { return owner; }

    Scheduler scheduler() { return scheduler; }
}
