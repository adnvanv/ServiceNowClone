package com.platform.api;

import com.platform.core.error.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Maps every error to the envelope { "error": { "message", "detail" } } with
 * the status carried by the exception: 400 bad input, 401 unauthenticated,
 * 403 ACL denial, 404 unknown table/record, 409 conflicts.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<Map<String, Object>> platform(PlatformException e) {
        return envelope(e.status(), e.getMessage(), e.detail());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> badPathParam(MethodArgumentTypeMismatchException e) {
        return envelope(HttpStatus.BAD_REQUEST, "Invalid value '" + e.getValue() + "'", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
        return envelope(HttpStatus.BAD_REQUEST, "Malformed request body", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "Not found", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", null);
    }

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String message,
                                                                String detail) {
        return ResponseEntity.status(status).body(Map.of("error",
                Map.of("message", message != null ? message : "",
                        "detail", detail != null ? detail : "")));
    }
}
