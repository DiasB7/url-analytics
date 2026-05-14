package com.example.urlanalytics.service;

import com.example.urlanalytics.entity.ShortUrl;
import com.example.urlanalytics.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

@Service
public class ShortCodeService implements ShortCodeGenerator {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int LENGTH = 7;

    @Override
    public String generate() {
        char[] code = new char[LENGTH];
        for (int i=0; i< LENGTH;i++) {
            code[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        }
        return String.valueOf(code);
    }

    //Collisions after truncation. A 7-char base62 prefix of a 32-byte hash has plenty of collisions over time
    //If the same person wants two short URLs for one long URL, cant get thme
    //compute waste. url to sha256 then - 99%
    public String generateHash(String longUrl) {
        String hashed = sha256(longUrl);
        return hashed.substring(0, 6);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }
}
