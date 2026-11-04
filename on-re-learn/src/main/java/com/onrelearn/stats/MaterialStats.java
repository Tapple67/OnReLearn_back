package com.onrelearn.stats;

import java.time.Instant;
import java.util.*;

// * Pure statistics over already authorized source data. No writes, regrading or provider calls.
public final class MaterialStats {
    private MaterialStats() {}
    public record Grade(int year, int term) {}
    public record Material(String id, String title, int week, String subjectName, Grade grade) {}
    public record Item(String id, String type, String topic) {}
    public record Answer(String quizItemId, Boolean isCorrect) {}
    public record Attempt(String id, String status, Integer score, Integer maxScore,
                          Instant submittedAt, List<Answer> answers) {
        public Attempt { answers = List.copyOf(answers); }
    }
    public record QuizSet(String purpose, List<Item> items, List<Attempt> attempts) {
        public QuizSet { items = List.copyOf(items); attempts = List.copyOf(attempts); }
    }
    public record Accuracy(long total, long correct, long wrong, double accuracy) {}
    public record Purpose(long attempts, long total, long correct, long wrong, double accuracy) {}
    public record Summary(long totalAttempts, long totalQuestions, long totalCorrect, long totalWrong,
                          double accuracy, double averageScore, int bestScore, int latestScore) {}
    public record Understanding(String level, String label, String message) {}
    public record Trend(String attemptId, Instant submittedAt, int score, int maxScore,
                        double accuracy, String purpose) {}
    public record Response(Material material, Summary summary, Understanding understanding,
                           Map<String, Accuracy> typeStats, Map<String, Purpose> purposeStats,
                           List<Trend> trend, List<MaterialTopicStats.Topic> weakTopics,
                           List<String> recommendations) {
        public Response {
            typeStats = Collections.unmodifiableMap(new LinkedHashMap<>(typeStats));
            purposeStats = Collections.unmodifiableMap(new LinkedHashMap<>(purposeStats));
            trend = List.copyOf(trend); weakTopics = List.copyOf(weakTopics);
            recommendations = List.copyOf(recommendations);
        }
    }

    public static Response build(Material material, List<QuizSet> sets) {
        var types = new LinkedHashMap<String, long[]>();
        for (var type : List.of("mcq", "tf", "short")) types.put(type, new long[2]);
        var purposes = new LinkedHashMap<String, long[]>();
        for (var purpose : List.of("GENERAL", "WRONG_REVIEW", "WEAK_TOPIC")) purposes.put(purpose, new long[3]);
        var trend = new ArrayList<Trend>();
        var topicAnswers = new ArrayList<MaterialTopicStats.Answer>();
        long total = 0, correct = 0;
        for (var set : sets) {
            String purpose = purposes.containsKey(set.purpose()) ? set.purpose() : "GENERAL";
            var purposeCount = purposes.get(purpose);
            var items = new HashMap<String, Item>();
            for (var item : set.items()) items.put(item.id(), item);
            for (var attempt : set.attempts()) {
                if (!"SUBMITTED".equals(attempt.status()) && !"GRADED".equals(attempt.status())) continue;
                purposeCount[0]++;
                int score = attempt.score() == null ? 0 : attempt.score();
                int maxScore = attempt.maxScore() == null ? 0 : attempt.maxScore();
                trend.add(new Trend(attempt.id(), attempt.submittedAt(), score, maxScore,
                    percent(score, maxScore), purpose));
                for (var answer : attempt.answers()) {
                    var item = items.get(answer.quizItemId());
                    if (item == null || !types.containsKey(item.type())) continue;
                    var typeCount = types.get(item.type());
                    total++; typeCount[0]++; purposeCount[1]++;
                    // ! Null grades count as wrong for statistics only; preserve the stored grade.
                    if (Boolean.TRUE.equals(answer.isCorrect())) {
                        correct++; typeCount[1]++; purposeCount[2]++;
                    }
                    topicAnswers.add(new MaterialTopicStats.Answer(item.type(), item.topic(), answer.isCorrect()));
                }
            }
        }
        // * B10: global submission chronology, independent of set creation/start order.
        // Missing legacy timestamps precede dated submissions; IDs break timestamp ties deterministically.
        trend.sort(Comparator.comparing(Trend::submittedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Trend::attemptId));
        var typeStats = new LinkedHashMap<String, Accuracy>();
        types.forEach((key, count) -> typeStats.put(key,
            new Accuracy(count[0], count[1], count[0] - count[1], percent(count[1], count[0]))));
        var purposeStats = new LinkedHashMap<String, Purpose>();
        purposes.forEach((key, count) -> purposeStats.put(key,
            new Purpose(count[0], count[1], count[2], count[1] - count[2], percent(count[2], count[1]))));
        double accuracy = percent(correct, total);
        var weak = MaterialTopicStats.weakTopics(topicAnswers);
        double average = trend.isEmpty() ? 0 : round1(trend.stream().mapToDouble(Trend::score).average().orElse(0));
        int best = trend.stream().mapToInt(Trend::score).max().orElse(0);
        int latest = trend.isEmpty() ? 0 : trend.get(trend.size() - 1).score();
        return new Response(material, new Summary(trend.size(), total, correct, total - correct,
            accuracy, average, best, latest), trend.isEmpty()
                ? new Understanding("NONE", "통계 없음", "아직 제출된 퀴즈 결과가 없습니다.")
                : understanding(accuracy),
            typeStats, purposeStats, trend, weak, trend.isEmpty()
                ? List.of("먼저 이 자료에 대해 퀴즈를 풀어보세요.")
                : recommendations(accuracy, typeStats, weak));
    }

    private static double round1(double value) { return Math.round(value * 10) / 10.0; }
    private static double percent(long correct, long total) {
        return total <= 0 ? 0 : round1((double) correct / total * 100);
    }
    private static Understanding understanding(double accuracy) {
        if (accuracy >= 85) return new Understanding("HIGH", "이해도 높음", "이 자료의 전반적인 이해도가 높은 편입니다.");
        if (accuracy >= 70) return new Understanding("MID_HIGH", "이해도 보통 이상", "핵심 내용은 이해했지만 일부 개념 점검이 필요합니다.");
        if (accuracy >= 50) return new Understanding("MID_LOW", "일부 복습 필요", "이 자료의 핵심 개념을 한 번 더 점검하는 것이 좋습니다.");
        return new Understanding("LOW", "복습 권장", "이 자료는 전반적인 복습이 필요합니다.");
    }
    private static List<String> recommendations(double accuracy, Map<String, Accuracy> types,
                                                List<MaterialTopicStats.Topic> weak) {
        var messages = new LinkedHashSet<String>();
        if (accuracy < 50) messages.add("이 자료는 전반적으로 다시 점검하는 것이 좋습니다.");
        else if (accuracy < 70) messages.add("핵심 개념을 중심으로 한 번 더 복습해보세요.");
        var labels = Map.of("short", "서술형 문제에서 개념 설명이 부족할 수 있습니다.",
            "mcq", "객관식 문제에서 선지 구분 연습이 필요합니다.",
            "tf", "OX 문제에서 기본 개념 구분을 다시 확인해보세요.");
        for (var type : List.of("short", "mcq", "tf")) {
            var stat = types.get(type);
            if (stat.total() > 0 && stat.accuracy() < 60) messages.add(labels.get(type));
        }
        weak.stream().limit(3).forEach(topic -> messages.add("\"" + topic.topic() + "\" 주제를 점검해보세요."));
        return messages.stream().limit(5).toList();
    }
}
