package com.nexora;

public enum ErrorCode {
    INVALID_REQUEST("The request is invalid."),
    RUN_NOT_FOUND("The run was not found."),
    INTERNAL_AUTH_FAILED("Internal authentication failed."),
    UPSTREAM_UNAVAILABLE("The AI service is temporarily unavailable."),
    UPSTREAM_STREAM_INVALID("The AI service returned an invalid response."),
    UPSTREAM_STREAM_TRUNCATED("The AI service stream ended before the run finished."),
    RUN_TIMEOUT("The run exceeded the maximum allowed time."),
    INTERNAL_ERROR("An internal error occurred.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    // Fixed per code so no exception text can reach a client.
    public String message() {
        return message;
    }
}
