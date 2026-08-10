package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import org.redisson.api.RBucket;
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
