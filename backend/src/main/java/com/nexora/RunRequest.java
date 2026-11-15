package com.nexora;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record RunRequest(String question) {

    static final int MAX_QUESTION_LENGTH = 2000;

    public RunRequest {
        // Code points, not UTF-16 units, so the limit agrees with the Python service.
        if (question == null || question.isEmpty()
                || question.codePointCount(0, question.length()) > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must be 1 to " + MAX_QUESTION_LENGTH + " characters");
        }
    }

    // Strict here only, so a global switch does not also reject extra fields in upstream events.
    @JsonAnySetter
    void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
