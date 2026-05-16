package com.example.urlanalytics.dto;

import java.time.Instant;

public record ShortUrlResponse(
        Long id,
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {}
