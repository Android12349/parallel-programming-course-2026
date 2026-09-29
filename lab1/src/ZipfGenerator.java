import java.util.Random;

public class ZipfGenerator {
    public static int[] generate(int size, long seed) {
        double[] cumulative = new double[1023];
        double totalWeight = 0;

        for (int k = 1; k <= 1023; k++) {
            totalWeight += 1.0 / Math.pow(k, 1.15);
        }

        double accumulatedWeight = 0;
        for (int k = 1; k <= 1023; k++) {
            accumulatedWeight += 1.0 / Math.pow(k, 1.15);
            cumulative[k - 1] = accumulatedWeight / totalWeight;
        }
        cumulative[1022] = 1.0;

        Random random = new Random(seed);
        int[] values = new int[size];

        for (int i = 0; i < size; i++) {
            double target = random.nextDouble();
            int left = 0;
            int right = cumulative.length - 1;

            while (left < right) {
                int middle = (left + right) / 2;
                if (target < cumulative[middle]) {
                    right = middle;
                } else {
                    left = middle + 1;
                }
            }

            values[i] = left + 1;
        }

        return values;
    }
}
