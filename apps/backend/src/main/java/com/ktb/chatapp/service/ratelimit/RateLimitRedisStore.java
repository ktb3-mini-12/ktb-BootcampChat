package com.ktb.chatapp.service.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.RateLimit;
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
 * Redis-backed RateLimitStore for shared counters across instances.
 */
@Component
@ConditionalOnBean(RedissonClient.class)
@RequiredArgsConstructor
@Slf4j
public class RateLimitRedisStore implements RateLimitStore {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:client:";

    @Override
    public Optional<RateLimit> findByClientId(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        RBucket<RateLimit> bucket = redissonClient.getBucket(buildKey(clientId), new JsonJacksonCodec(objectMapper));
        return Optional.ofNullable(bucket.get());
    }

    @Override
    public RateLimit save(RateLimit rateLimit) {
        if (rateLimit == null || rateLimit.getClientId() == null) {
            throw new IllegalArgumentException("RateLimit or clientId cannot be null");
        }
        long ttlSeconds = computeTtlSeconds(rateLimit.getExpiresAt());
        RBucket<RateLimit> bucket = redissonClient.getBucket(buildKey(rateLimit.getClientId()), new JsonJacksonCodec(objectMapper));
        bucket.set(rateLimit, ttlSeconds, TimeUnit.SECONDS);
        return rateLimit;
    }

    private String buildKey(String clientId) {
        return RATE_LIMIT_KEY_PREFIX + clientId;
    }

    private long computeTtlSeconds(Instant expiresAt) {
        if (expiresAt == null) {
            return TimeUnit.MINUTES.toSeconds(1); // fallback TTL
        }
        long ttl = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(1, ttl);
    }
}
