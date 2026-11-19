package com.pixelchiss.gameservice.bot;

import java.util.function.DoubleSupplier;

/** Full precision categorical distribution, independent of the position of the answer key. */
public final class TriviaSelection {
    private TriviaSelection() {}

    public static double[] weights(int options, int correct, double probability) {
        if (options < 2 || correct < 0 || correct >= options || !Double.isFinite(probability)
                || probability < 0 || probability > 1) throw new IllegalArgumentException("Invalid question distribution");
        double[] result = new double[options];
        java.util.Arrays.fill(result, (1 - probability) / (options - 1));
        result[correct] = probability;
        return result;
    }

    public static int select(int options, int correct, double probability, DoubleSupplier random) {
        double[] weights = weights(options, correct, probability);
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1) throw new IllegalArgumentException("Sample outside [0,1)");
        int lastPositive = correct;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) lastPositive = i;
            sample -= weights[i];
            if (sample < 0) return i;
        }
        return lastPositive;
    }
}
