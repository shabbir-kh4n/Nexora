package com.nexora;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return respond(ex.status(), ex.code());
    }

    // Malformed JSON, unknown fields and record constructor failures all arrive as unreadable bodies.
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        HttpMediaTypeNotSupportedException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ErrorResponse> handleInvalidRequest() {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) throws Exception {
        // Other framework exceptions (unknown path, wrong method) keep their own status.
        if (ex instanceof org.springframework.web.ErrorResponse) {
            throw ex;
        }
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, ErrorCode code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }
}
