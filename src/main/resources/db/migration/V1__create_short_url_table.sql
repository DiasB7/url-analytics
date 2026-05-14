-- V1__create_short_url_table.sql
CREATE TABLE short_urls (
                            id BIGSERIAL PRIMARY KEY,
                            short_code VARCHAR(10) NOT NULL UNIQUE,
                            long_url TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            expires_at TIMESTAMPTZ
);