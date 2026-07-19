package com.platform.core.error;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends PlatformException {

    public UnauthorizedException(String message) {
        this(message, null);
    }

    public UnauthorizedException(String message, String detail) {
        super(HttpStatus.UNAUTHORIZED, message, detail);
    }
}
