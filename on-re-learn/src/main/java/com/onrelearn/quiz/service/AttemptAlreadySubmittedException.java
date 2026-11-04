package com.onrelearn.quiz.service;

public class AttemptAlreadySubmittedException extends RuntimeException {
    private final String attemptId;
    public AttemptAlreadySubmittedException(String attemptId) {
        super("Quiz already submitted");
        this.attemptId=attemptId;
    }
    public String getAttemptId() { return attemptId; }
}
