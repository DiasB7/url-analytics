package com.example.urlanalytics.Exception;

public class ShortCodeNotFoundException extends RuntimeException {
    public ShortCodeNotFoundException(String code) {
        super("Short code not found: " + code);
    }
}
