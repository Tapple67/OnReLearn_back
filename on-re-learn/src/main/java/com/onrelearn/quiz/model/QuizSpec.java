package com.onrelearn.quiz.model;

public record QuizSpec(int mcqCount, int tfCount, int shortCount) {
    public static final QuizSpec DEFAULT = new QuizSpec(6, 3, 1);
    public static final QuizSpec REVIEW = new QuizSpec(5, 3, 2);

    public QuizSpec {
        // ! Reject invalid counts before building prompts or spending a provider request.
        long total = (long) mcqCount + tfCount + shortCount;
        if (mcqCount < 0 || tfCount < 0 || shortCount < 0 || total < 1 || total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Quiz counts must be nonnegative with a positive integer total");
        }
    }

    public int totalCount() { return mcqCount + tfCount + shortCount; }
}
