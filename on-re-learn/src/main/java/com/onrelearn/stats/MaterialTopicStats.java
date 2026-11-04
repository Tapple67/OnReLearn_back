package com.onrelearn.stats;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

// * Aggregate answer rows from owned, completed attempts; this function never loads or changes records.
public final class MaterialTopicStats {
    private MaterialTopicStats() {}

    public record Answer(String type, String topic, Boolean isCorrect) {}
    public record Topic(String topic, long total, long correct, long wrong, double accuracy) {}

    public static List<Topic> weakTopics(List<Answer> answers) {
        var counts = new LinkedHashMap<String, long[]>();
        for (var answer : answers) {
            if (!"mcq".equals(answer.type()) && !"tf".equals(answer.type()) && !"short".equals(answer.type())) continue;
            String topic = answer.topic() == null ? "" :
                answer.topic().replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
            if (topic.isEmpty()) topic = "기타 개념";
            var count = counts.computeIfAbsent(topic, ignored -> new long[2]);
            count[0]++;
            // ! Legacy statistics count null grades as wrong; wrong-review generation uses false only.
            if (Boolean.TRUE.equals(answer.isCorrect())) count[1]++;
        }
        return counts.entrySet().stream().map(entry -> {
            var count = entry.getValue();
            return new Topic(entry.getKey(), count[0], count[1], count[0] - count[1],
                Math.round((double) count[1] / count[0] * 1000) / 10.0);
        }).filter(topic -> topic.total() >= 2 && (topic.accuracy() < 60 || topic.wrong() >= 2))
            // * Stable ties preserve source encounter order, matching the legacy Map and stable sort.
            .sorted(Comparator.comparingDouble(Topic::accuracy)
                .thenComparing(Comparator.comparingLong(Topic::wrong).reversed()))
            .limit(5).toList();
    }
}
