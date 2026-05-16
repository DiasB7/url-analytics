package com.example.urlanalytics.service;

import com.example.urlanalytics.dto.CreateShortUrlRequest;
import com.example.urlanalytics.dto.ShortUrlResponse;
import com.example.urlanalytics.entity.ShortUrl;
import com.example.urlanalytics.repository.ShortUrlRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UrlService {
    private final ShortUrlRepository repo;
    private final ShortCodeService shortCodeService;
    private static final Logger log = LoggerFactory.getLogger(UrlService.class);
    private final static String BASE_URL = "http://localhost:8080/";
    private final static int MAX_ATTEMPTS = 10;

    public UrlService(ShortUrlRepository shortUrlRepository, ShortCodeService shortCodeService) {
        this.repo = shortUrlRepository;
        this.shortCodeService = shortCodeService;
    }

    @Transactional
    public ShortUrlResponse create(CreateShortUrlRequest req) {
        //business validation: future longUrl check for blocked domain and etc.
        for (int i = 0; i<MAX_ATTEMPTS; i++) {
            String code = shortCodeService.generate();
            try {
                ShortUrl shortUrl = new ShortUrl(code, req.LongUrl(), req.expiresAt());
                ShortUrl saved = repo.saveAndFlush(shortUrl);
                return toResponse(saved);
            } catch (DataIntegrityViolationException e) {
                log.error("unique collision — retry");
            }
        }
        throw new IllegalStateException("could not generate unique code after " + MAX_ATTEMPTS);
    }

    private ShortUrlResponse toResponse(ShortUrl saved) {
        return new ShortUrlResponse(
                saved.getId(),
                saved.getShortCode(),
                BASE_URL + saved.getShortCode(),
                saved.getLongUrl(),
                saved.getCreatedAt(),
                saved.getExpiresAt()
        );
    }
}
