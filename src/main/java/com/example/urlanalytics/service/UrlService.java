package com.example.urlanalytics.service;

import com.example.urlanalytics.exception.ShortCodeNotFoundException;
import com.example.urlanalytics.exception.UrlExpiredException;
import com.example.urlanalytics.dto.CreateShortUrlRequest;
import com.example.urlanalytics.dto.ShortUrlResponse;
import com.example.urlanalytics.dto.StatsResponse;
import com.example.urlanalytics.entity.ShortUrl;
import com.example.urlanalytics.repository.ClickEventRepository;
import com.example.urlanalytics.repository.ShortUrlRepository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class UrlService {
    private final ShortUrlRepository shortUrlRepository;
    private final ClickEventRepository clickEventRepository;
    private final ShortCodeGenerator shortCodeGenerator;

    @Value("${app.base-url}")
    private String BASE_URL;

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);
    private final static int MAX_ATTEMPTS = 10;

    public UrlService(ShortUrlRepository shortUrlRepository, ClickEventRepository clickEventRepository, ShortCodeGenerator shortCodeGenerator) {
        this.shortUrlRepository = shortUrlRepository;
        this.clickEventRepository = clickEventRepository;
        this.shortCodeGenerator = shortCodeGenerator;
    }

    @Transactional
    public ShortUrlResponse create(CreateShortUrlRequest req) {
        //business validation: future longUrl check for blocked domain and etc.
        for (int i = 0; i<MAX_ATTEMPTS; i++) {
            String code = shortCodeGenerator.generate();
            try {
                ShortUrl shortUrl = new ShortUrl(code, req.longUrl(), req.expiresAt());
                ShortUrl saved = shortUrlRepository.saveAndFlush(shortUrl);
                return toShortUrlResponse(saved);
            } catch (DataIntegrityViolationException e) {
                log.error("unique collision — retry");
            }
        }
        throw new IllegalStateException("could not generate unique code after " + MAX_ATTEMPTS);
    }

    private ShortUrlResponse toShortUrlResponse(ShortUrl saved) {
        return new ShortUrlResponse(
                saved.getId(),
                saved.getShortCode(),
                BASE_URL + saved.getShortCode(),
                saved.getLongUrl(),
                saved.getCreatedAt(),
                saved.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        ShortUrl url = shortUrlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
            throw new UrlExpiredException();
        }
        return url.getLongUrl();
    }

    @Transactional(readOnly = true)
    public StatsResponse getStats(String shortCode) {
        ShortUrl url = shortUrlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        long totalClicks = clickEventRepository.countByShortUrlId(url.getId());
        return toStateResponse(url, totalClicks);
    }

    private StatsResponse toStateResponse(ShortUrl url, Long totalClicks) {
        return new StatsResponse(
                url.getShortCode(),
                url.getLongUrl(),
                totalClicks,
                url.getCreatedAt()
        );
    }
}
