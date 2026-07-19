package com.platform.core.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends PlatformException {

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, String detail) {
        super(HttpStatus.CONFLICT, message, detail);
    }
}
