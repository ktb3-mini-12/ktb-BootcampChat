package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Local in-memory implementation of ChatDataStore using ConcurrentHashMap.
 * Thread-safe storage for chat-related data without external dependencies.
 */
@Component
@ConditionalOnMissingBean(ChatDataStore.class)
public class LocalChatDataStore implements ChatDataStore {
    
    private final ConcurrentHashMap<String, Object> storage = new ConcurrentHashMap<>();
    
    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }
    
    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }
    
    @Override
    public void delete(String key) {
        storage.remove(key);
    }
    
    @Override
    public int size() {
        return storage.size();
    }
}
