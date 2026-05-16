package com.example.urlanalytics.dto;

import java.time.Instant;

public record StatsResponse(
   String shortCode,
   String longUrl,
   long totalClicks,
   Instant createdAt
) {}
