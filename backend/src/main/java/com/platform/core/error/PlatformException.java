package com.platform.core.error;

import org.springframework.http.HttpStatus;

/**
 * Base of all errors the platform intentionally returns to a client. The API
 * layer maps the status straight onto the HTTP response, so throwing one of
 * the subclasses from anywhere in the core is enough to produce a correct,
 * consistently-shaped error payload.
 */
public abstract class PlatformException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;

    protected PlatformException(HttpStatus status, String message, String detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return detail;
    }
}
