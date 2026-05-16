package com.example.urlanalytics.controller;

import com.example.urlanalytics.dto.CreateShortUrlRequest;
import com.example.urlanalytics.dto.ShortUrlResponse;
import com.example.urlanalytics.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
