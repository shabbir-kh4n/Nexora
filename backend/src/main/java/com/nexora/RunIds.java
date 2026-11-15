package com.nexora;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class RunIds {

    // Same expression as the RunId schema in both OpenAPI documents; a test keeps them equal.
    static final String PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private static final Pattern CANONICAL = Pattern.compile(PATTERN);

    private RunIds() {
    }

    public static boolean isValid(String value) {
        // matches() spans the whole input, so a trailing newline is rejected unlike a bare `$` find.
        return value != null && CANONICAL.matcher(value).matches();
    }

    public static String require(String value) {
        if (!isValid(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
        return value;
    }
}
