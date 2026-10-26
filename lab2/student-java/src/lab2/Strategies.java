package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/* Стратегии выбора следующего потока.
 * FirstAlive — этап 1, Replay — этап 2, RandomPick — этап 3, Dfs — этап 4 (ограничение bound — этап 5).
 * enabled — номера потоков, которые могут сделать шаг, по возрастанию; last — прошлый выбранный (-1 в начале). */
public final class Strategies {
    private Strategies() { }

    /** Всегда первый поток из enabled. */
    public static final class FirstAlive implements Strategy {
        public int pick(List<Integer> enabled, int last) {
            if (enabled.isEmpty()) throw new IllegalArgumentException("enabled is empty");
            return enabled.get(0);
        }
    }

    /** Проигрывает трассу: на i-м решении выбирает поток trace[i] ('A' -> 0, 'B' -> 1). */
    public static final class Replay implements Strategy {
        private final String trace;
        private int position;

        public Replay(String trace) {
            if (trace == null) throw new NullPointerException("trace");
            this.trace = trace;
        }

        /** Сбросить позицию в трассе перед прогоном. */
        public void beginRun() { position = 0; }

        public int pick(List<Integer> enabled, int last) {
            if (position >= trace.length()) {
                throw new IllegalArgumentException("trace ended at decision " + position);
            }
            char letter = trace.charAt(position++);
            int chosen = letter - 'A';
            if (!enabled.contains(chosen)) {
                throw new IllegalArgumentException("thread " + letter + " is not enabled");
            }
            return chosen;
        }
    }

    /** Случайный поток: enabled.get(rnd.nextInt(enabled.size())), rnd = new java.util.Random(seed). */
    public static final class RandomPick implements Strategy {
        private final Random rnd;

        public RandomPick(long seed) { rnd = new Random(seed); }

        public int pick(List<Integer> enabled, int last) {
            if (enabled.isEmpty()) throw new IllegalArgumentException("enabled is empty");
            return enabled.get(rnd.nextInt(enabled.size()));
        }
    }

    /** Stateless DFS по дереву выборов: каждый прогон с нуля, повторяет префикс прошлого и берёт следующий вариант.
     *  bound — максимум вытеснений (этап 5); для dfs runner передаёт Integer.MAX_VALUE. */
    public static final class Dfs implements Strategy {
        private final int bound;
        private final List<List<Integer>> options = new ArrayList<>();
        private final List<Integer> choices = new ArrayList<>();
        private int position;

        public Dfs(int bound) {
            if (bound < 0) throw new IllegalArgumentException("bound must be non-negative");
            this.bound = bound;
        }

        /** Подготовиться к новому прогону (позиция в пути, счётчик вытеснений). */
        public void beginRun() { position = 0; }

        public int pick(List<Integer> enabled, int last) {
            if (enabled.isEmpty()) throw new IllegalArgumentException("enabled is empty");

            if (position == options.size()) {
                options.add(List.copyOf(enabled));
                choices.add(0);
            }

            List<Integer> variants = options.get(position);
            int chosen = variants.get(choices.get(position));
            if (!enabled.contains(chosen)) {
                throw new IllegalStateException("saved DFS choice is not enabled");
            }
            position++;
            return chosen;
        }

        /** Выбрать следующий путь; false — дерево обойдено. */
        public boolean endRun() {
            truncate(position);
            for (int i = position - 1; i >= 0; i--) {
                int next = choices.get(i) + 1;
                if (next < options.get(i).size()) {
                    choices.set(i, next);
                    truncate(i + 1);
                    return true;
                }
            }
            return false;
        }

        private void truncate(int size) {
            while (options.size() > size) options.remove(options.size() - 1);
            while (choices.size() > size) choices.remove(choices.size() - 1);
        }
    }
}
