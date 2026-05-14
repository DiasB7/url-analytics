CREATE TABLE click_events (
                              id BIGSERIAL PRIMARY KEY,
                              short_url_id BIGINT NOT NULL REFERENCES short_urls(id),
                              clicked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                              user_agent TEXT,
                              ip_address VARCHAR(45),
                              referrer TEXT
);

CREATE INDEX idx_click_events_url_time ON click_events(short_url_id, clicked_at);