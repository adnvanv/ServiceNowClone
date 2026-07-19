package com.platform.core.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends PlatformException {

    public BadRequestException(String message) {
        this(message, null);
    }

    public BadRequestException(String message, String detail) {
        super(HttpStatus.BAD_REQUEST, message, detail);
    }
}
