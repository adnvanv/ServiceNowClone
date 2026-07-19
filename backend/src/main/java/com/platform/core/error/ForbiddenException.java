package com.platform.core.error;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends PlatformException {

    public ForbiddenException(String message) {
        this(message, null);
    }

    public ForbiddenException(String message, String detail) {
        super(HttpStatus.FORBIDDEN, message, detail);
    }
}
