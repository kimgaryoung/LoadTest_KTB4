package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** Redis-backed shared store for Socket.IO user and room state. */
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;

    public RedisChatDataStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = bucket(key).get();
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
        bucket(key).set(value);
    }

    @Override
    public void delete(String key) {
        bucket(key).delete();
    }

    @Override
    public <T> T update(String key, Class<T> type, Supplier<T> initialValue, UnaryOperator<T> updater) {
        RLock lock = redissonClient.getLock(key + ":lock");
        lock.lock();
        try {
            T current = get(key, type).orElseGet(initialValue);
            T updated = updater.apply(current);
            if (updated == null) {
                delete(key);
            } else {
                set(key, updated);
            }
            return updated;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        int count = 0;
        for (String ignored : redissonClient.getKeys().getKeysByPattern("conn_users:userid:*")) {
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private RBucket<Object> bucket(String key) {
        return (RBucket<Object>) redissonClient.getBucket(key);
    }
}
