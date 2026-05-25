package com.malaysia.restaurant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> T run(String idempotencyKey, AuthService.AuthContext user, String action,
                     Supplier<T> actionSupplier, Class<T> resultType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return actionSupplier.get();
        }
        String cacheKey = "restaurant:idempotency:" + user.id() + ":" + action + ":" + sha256(idempotencyKey.trim());
        T cached = read(cacheKey, resultType);
        if (cached != null) {
            return cached;
        }

        String lockKey = cacheKey + ":lock";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            T completed = waitForCompleted(cacheKey, resultType);
            if (completed != null) {
                return completed;
            }
            throw new IllegalStateException("请求正在处理中，请稍后查看结果");
        }

        try {
            T doubleChecked = read(cacheKey, resultType);
            if (doubleChecked != null) {
                return doubleChecked;
            }
            T result = actionSupplier.get();
            redis.opsForValue().set(cacheKey, write(result), CACHE_TTL);
            return result;
        } finally {
            String currentLock = redis.opsForValue().get(lockKey);
            if (lockValue.equals(currentLock)) {
                redis.delete(lockKey);
            }
        }
    }

    private <T> T waitForCompleted(String cacheKey, Class<T> resultType) {
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
            T cached = read(cacheKey, resultType);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private <T> T read(String key, Class<T> resultType) {
        String value = redis.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(value, resultType);
        } catch (Exception ex) {
            redis.delete(key);
            return null;
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等结果序列化失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("幂等键生成失败", ex);
        }
    }
}
