package com.example.urlanalytics.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;

public record CreateShortUrlRequest(
        @NotBlank(message = "longUrl must not be blank")
        @Size(max = 2048, message = "longUrl too long")
        @Pattern(regexp = "^https?://.+", message = "longUrl must start with http:// or https://")
        String LongUrl,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {}
