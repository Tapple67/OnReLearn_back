package com.onrelearn;

import com.onrelearn.stats.MaterialTopicStats;
import com.onrelearn.stats.MaterialTopicStats.Answer;
import com.onrelearn.stats.MaterialTopicStats.Topic;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialTopicStatsTests {
    @Test void preservesLegacyNullGradesTopicNormalizationAndTypeFilter() {
        var input = List.of(new Answer("mcq", " A ", false), new Answer("short", "A", true),
            new Answer("tf", "\u00a0\uFEFFA\u3000", null), new Answer("essay", "A", false),
            new Answer("tf", null, null), new Answer("mcq", " ", false),
            new Answer("mcq", "a", false));
        assertEquals(List.of(new Topic("기타 개념", 2, 0, 2, 0),
            new Topic("A", 3, 1, 2, 33.3)), MaterialTopicStats.weakTopics(input));
        assertEquals(" A ", input.get(0).topic());
        assertTrue(MaterialTopicStats.weakTopics(List.of()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> MaterialTopicStats.weakTopics(input).clear());
    }

    @Test void appliesMinimumSamplesOrWrongThresholdStableRankingAndFiveLimit() {
        var rows = new ArrayList<Answer>();
        // 60% remains weak when wrong count is two; 80% with one wrong does not.
        add(rows, "sixty", 3, 2);
        add(rows, "eighty", 4, 1);
        add(rows, "single", 0, 1);
        add(rows, "half", 1, 1);
        add(rows, "moreWrong", 0, 3);
        for (var name : List.of("first", "second", "third", "fourth", "fifth")) add(rows, name, 0, 2);
        assertEquals(List.of("moreWrong", "first", "second", "third", "fourth"),
            MaterialTopicStats.weakTopics(rows).stream().map(Topic::topic).toList());
        assertEquals(List.of("half", "sixty"),
            MaterialTopicStats.weakTopics(rows.subList(0, 13)).stream().map(Topic::topic).toList());
    }

    private static void add(List<Answer> rows, String topic, int correct, int wrong) {
        for (int i = 0; i < correct; i++) rows.add(new Answer("mcq", topic, true));
        for (int i = 0; i < wrong; i++) rows.add(new Answer("mcq", topic, false));
    }
}
