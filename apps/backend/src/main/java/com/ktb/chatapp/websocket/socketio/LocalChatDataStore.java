package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Local in-memory implementation of ChatDataStore using ConcurrentHashMap.
 * Thread-safe storage for chat-related data without external dependencies.
 */
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
    public <T> T update(String key, Class<T> type, Supplier<T> initialValue, UnaryOperator<T> updater) {
        Object updated = storage.compute(key, (ignored, value) -> {
            T current = value == null ? initialValue.get() : type.cast(value);
            return updater.apply(current);
        });
        return updated == null ? null : type.cast(updated);
    }
    
    @Override
    public int size() {
        return storage.size();
    }
}
