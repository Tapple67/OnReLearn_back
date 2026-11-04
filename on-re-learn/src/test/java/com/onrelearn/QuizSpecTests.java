package com.onrelearn;

import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.model.QuizPurpose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuizSpecTests {
    @Test
    void preservesGeneralReviewAndSingleTypeSpecifications() {
        assertEquals(new QuizSpec(6, 3, 1), QuizSpec.DEFAULT);
        assertEquals(new QuizSpec(5, 3, 2), QuizSpec.REVIEW);
        assertEquals(10, QuizSpec.DEFAULT.totalCount());
        assertEquals(10, QuizSpec.REVIEW.totalCount());
        assertEquals(4, new QuizSpec(0, 0, 4).totalCount());
        assertArrayEquals(new String[] {"GENERAL", "WRONG_REVIEW", "WEAK_TOPIC"},
            java.util.Arrays.stream(QuizPurpose.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void rejectsEmptyNegativeAndOverflowingSpecifications() {
        for (int[] counts : new int[][] {{0,0,0}, {-1,3,1}, {6,-1,1}, {6,3,-1}, {Integer.MAX_VALUE,1,0}}) {
            assertThrows(IllegalArgumentException.class, () -> new QuizSpec(counts[0], counts[1], counts[2]));
        }
    }
}
