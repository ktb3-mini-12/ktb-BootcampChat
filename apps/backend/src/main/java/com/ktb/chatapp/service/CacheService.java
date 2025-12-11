package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 로컬 메모리 캐시 서비스
 * User/Room 조회 결과를 캐싱하여 DB 부하 감소
 * TTL: 5분 (참여자 목록 변경 등 반영을 위해)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;

    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    // 캐시 저장소
    private final Map<String, CacheEntry<User>> userCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Room>> roomCache = new ConcurrentHashMap<>();

    /**
     * User 조회 (캐시 우선)
     */
    public Optional<User> findUserById(String userId) {
        if (userId == null) return Optional.empty();

        CacheEntry<User> cached = userCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.value());
        }

        Optional<User> user = userRepository.findById(userId);
        userCache.put(userId, new CacheEntry<>(user.orElse(null), System.currentTimeMillis()));
        return user;
    }

    /**
     * Room 조회 (캐시 우선)
     */
    public Optional<Room> findRoomById(String roomId) {
        if (roomId == null) return Optional.empty();

        CacheEntry<Room> cached = roomCache.get(roomId);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.value());
        }

        Optional<Room> room = roomRepository.findById(roomId);
        roomCache.put(roomId, new CacheEntry<>(room.orElse(null), System.currentTimeMillis()));
        return room;
    }

    /**
     * User 캐시 무효화 (프로필 업데이트 시 호출)
     */
    public void evictUser(String userId) {
        userCache.remove(userId);
    }

    /**
     * Room 캐시 무효화 (참여자 변경 시 호출)
     */
    public void evictRoom(String roomId) {
        roomCache.remove(roomId);
    }

    /**
     * 캐시 엔트리 (값 + 생성 시간)
     */
    private record CacheEntry<T>(T value, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}