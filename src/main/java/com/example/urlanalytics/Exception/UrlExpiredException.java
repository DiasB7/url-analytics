package com.example.urlanalytics.Exception;

public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException() {
        super("Shortened Url is already expired");
    }
}
