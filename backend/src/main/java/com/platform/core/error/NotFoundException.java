package com.platform.core.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends PlatformException {

    public NotFoundException(String message) {
        this(message, null);
    }

    public NotFoundException(String message, String detail) {
        super(HttpStatus.NOT_FOUND, message, detail);
    }
}
