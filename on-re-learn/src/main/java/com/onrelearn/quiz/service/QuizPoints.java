package com.onrelearn.quiz.service;

import java.util.*;
import java.util.stream.IntStream;

public final class QuizPoints {
    private QuizPoints() {}

    public static List<Integer> normalize(List<Double> source) {
        int count = source.size();
        if (count == 0) return List.of();
        if (count >= 100) return Collections.nCopies(count, 1);
        double[] weights = source.stream().mapToDouble(value ->
            value != null && Double.isFinite(value) && value > 0 ? value : 1).toArray();
        // * Scaling prevents overflow without changing relative weights.
        double max = Arrays.stream(weights).max().orElseThrow();
        for (int i = 0; i < count; i++) weights[i] /= max;
        double total = Arrays.stream(weights).sum();
        int[] points = new int[count];
        double[] fractions = new double[count];
        int left = 100 - count;
        for (int i = 0; i < count; i++) {
            double extra = weights[i] / total * (100 - count);
            int floor = (int) Math.floor(extra);
            points[i] = 1 + floor;
            fractions[i] = extra - floor;
            left -= floor;
        }
        // * Equal remainders retain source order, matching the legacy stable sort.
        var order = IntStream.range(0, count).boxed()
            .sorted(Comparator.<Integer>comparingDouble(i -> fractions[i]).reversed()).toList();
        for (int i = 0; i < left; i++) points[order.get(i % count)]++;
        return Arrays.stream(points).boxed().toList();
    }
}
