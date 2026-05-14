package com.example.urlanalytics.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "click_events")
public class ClickEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;
    @JoinColumn(name = "short_url_id")
    private Long shortUrlId;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "referrer")
    private String referrer;

    protected ClickEvent() {
    }

    public ClickEvent(Instant clickedAt, Long shortUrlId, String userAgent, String ipAddress, String referrer) {
        this.clickedAt = clickedAt;
        this.shortUrlId = shortUrlId;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        this.referrer = referrer;
    }

    public Long getId() {
        return id;
    }

    public Instant getClickedAt() {
        return clickedAt;
    }

    public Long getShortUrlId() {
        return shortUrlId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getReferrer() {
        return referrer;
    }
}
