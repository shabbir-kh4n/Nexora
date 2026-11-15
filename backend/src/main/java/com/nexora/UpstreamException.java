package com.nexora;

// The message is only the code name: upstream text and URLs must never reach a client.
public class UpstreamException extends RuntimeException {

    private final ErrorCode code;

    public UpstreamException(ErrorCode code) {
        this(code, null);
    }

    public UpstreamException(ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
