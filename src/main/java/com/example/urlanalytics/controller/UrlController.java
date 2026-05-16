package com.example.urlanalytics.controller;

import com.example.urlanalytics.dto.CreateShortUrlRequest;
import com.example.urlanalytics.dto.ShortUrlResponse;
import com.example.urlanalytics.dto.StatsResponse;
import com.example.urlanalytics.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping
    public ResponseEntity<ShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse resp = urlService.create(request);
        URI location = URI.create("/api/urls/" + resp.shortCode());
        return ResponseEntity.created(location).body(resp);
    }

    @GetMapping("/{shortCode:[a-zA-Z0-9]{1,7}}/stats")
    public ResponseEntity<StatsResponse> getUrlClickStats(@PathVariable String shortCode) {
        //TODO: daily breakdown
        StatsResponse resp = urlService.getStats(shortCode);
        return ResponseEntity.ok(resp);
    }
}
