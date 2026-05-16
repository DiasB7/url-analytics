package com.example.urlanalytics.exception;

public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException() {
        super("Shortened Url is already expired");
    }
}
