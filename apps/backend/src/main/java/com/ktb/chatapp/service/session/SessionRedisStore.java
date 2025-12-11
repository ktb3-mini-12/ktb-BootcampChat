package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.Session;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Redis-backed SessionStore for cross-instance session consistency.
 */
@Component
@ConditionalOnBean(RedissonClient.class)
@RequiredArgsConstructor
@Slf4j
public class SessionRedisStore implements SessionStore {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private static final String SESSION_KEY_PREFIX = "session:user:";

    @Override
    public Optional<Session> findByUserId(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        RBucket<Session> bucket = redissonClient.getBucket(buildKey(userId), new JsonJacksonCodec(objectMapper));
        return Optional.ofNullable(bucket.get());
    }

    @Override
    public Session save(Session session) {
        if (session == null || session.getUserId() == null) {
            throw new IllegalArgumentException("Session or userId cannot be null");
        }
        long ttlSeconds = computeTtlSeconds(session.getExpiresAt());
        RBucket<Session> bucket = redissonClient.getBucket(buildKey(session.getUserId()), new JsonJacksonCodec(objectMapper));
        bucket.set(session, ttlSeconds, TimeUnit.SECONDS);
        return session;
    }

    @Override
    public void deleteAll(String userId) {
        if (userId == null) return;
        redissonClient.getBucket(buildKey(userId)).delete();
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        RBucket<Session> bucket = redissonClient.getBucket(buildKey(userId), new JsonJacksonCodec(objectMapper));
        Session existing = bucket.get();
        if (existing != null && sessionId.equals(existing.getSessionId())) {
            bucket.delete();
        }
    }

    private String buildKey(String userId) {
        return SESSION_KEY_PREFIX + userId;
    }

    private long computeTtlSeconds(Instant expiresAt) {
        if (expiresAt == null) {
            return TimeUnit.MINUTES.toSeconds(30); // fallback to default TTL
        }
        long ttl = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(1, ttl);
    }
}
