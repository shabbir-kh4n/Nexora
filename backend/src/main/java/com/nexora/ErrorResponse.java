package com.nexora;

import java.util.Objects;

public record ErrorResponse(ErrorBody error) {

    public ErrorResponse {
        Objects.requireNonNull(error, "error");
    }

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(ErrorBody.of(code));
    }

    public record ErrorBody(ErrorCode code, String message) {

        public ErrorBody {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }

        public static ErrorBody of(ErrorCode code) {
            return new ErrorBody(code, code.message());
        }
    }
}
