package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Redis-backed ChatDataStore for cross-instance consistency.
 */
@Component
@ConditionalOnBean(RedissonClient.class)
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60; // 12h

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        RBucket<T> bucket = redissonClient.getBucket(key, new JsonJacksonCodec(objectMapper));
        T value = bucket.get();
        return Optional.ofNullable(value);
    }

    @Override
    public void set(String key, Object value) {
        // store with a generous TTL to avoid unbounded growth; refreshed on updates
        RBucket<Object> bucket = redissonClient.getBucket(key);
        bucket.set(value, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key).delete();
    }

    @Override
    public int size() {
        // size is approximate; counts keys with prefix would be expensive, use basic key count
        return Math.toIntExact(redissonClient.getKeys().count());
    }
}
