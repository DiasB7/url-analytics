package com.example.urlanalytics.controller;

import com.example.urlanalytics.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {

    private final UrlService service;

    public RedirectController(UrlService service) {
        this.service = service;
    }

    //possible path collisions like /favicon.ico
    @GetMapping("/{shortCode:[a-zA-Z0-9]{1,7}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String longUrl = service.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .build();
//        return ResponseEntity.status(302).header("Location", longUrl).build();
    }
}
