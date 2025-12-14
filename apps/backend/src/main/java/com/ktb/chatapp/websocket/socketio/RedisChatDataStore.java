package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Redis-backed ChatDataStore for cross-instance consistency.
 * Primary implementation when Redis is available.
 * Uses StringCodec to avoid @class type info issues with JsonJacksonCodec.
 */
@Slf4j
@Component
@Primary
@ConditionalOnBean(RedissonClient.class)
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60; // 12h

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        String json = bucket.get();
        if (json == null) {
            return Optional.empty();
        }
        try {
            // Set.class 요청 시 Set<String>으로 역직렬화하여 타입 안전성 보장
            if (Set.class.isAssignableFrom(type)) {
                JavaType setType = objectMapper.getTypeFactory()
                        .constructCollectionType(HashSet.class, String.class);
                Set<String> result = objectMapper.readValue(json, setType);
                return Optional.of((T) result);
            }
            T value = objectMapper.readValue(json, type);
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
            bucket.set(json, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key, StringCodec.INSTANCE).delete();
    }

    @Override
    public int size() {
        // size is approximate; counts keys with prefix would be expensive, use basic key count
        return Math.toIntExact(redissonClient.getKeys().count());
    }
}
