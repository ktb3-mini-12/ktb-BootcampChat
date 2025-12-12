package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.CacheService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final CacheService cacheService;

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest) {

        try {
            String sortField = pageRequest.isValidSortField() ? pageRequest.sortField() : "createdAt";
            String sortOrder = pageRequest.isValidSortOrder() ? pageRequest.sortOrder() : "desc";

            Sort.Direction direction = "desc".equals(sortOrder)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds";
            }

            PageRequest springPageRequest = PageRequest.of(
                pageRequest.page(),
                pageRequest.pageSize(),
                Sort.by(direction, sortField)
            );

            Page<Room> roomPage;
            if (pageRequest.search() != null && !pageRequest.search().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                    pageRequest.search().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            List<RoomResponse> roomResponses = roomPage.getContent().stream()
                .map(this::mapToRoomResponse)
                .collect(Collectors.toList());

            PageMetadata metadata = new PageMetadata(roomPage.hasNext());

            return new RoomsResponse(
                true,
                roomResponses,
                metadata
            );

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return new RoomsResponse(
                false,
                List.of(),
                null
            );
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(isMongoConnected)
                    .latency(latency)
                    .build());

            return new HealthResponse(true, null, services, lastActivity);

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return new HealthResponse(false, null, new HashMap<>(), null);
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.name().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.password() != null && !createRoomRequest.password().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.password()));
        }

        Room savedRoom = roomRepository.save(room);
        cacheService.evictRoom(savedRoom.getId()); // 참여자 목록 최신화

        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        if (!room.getParticipantIds().contains(user.getId())) {
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
            cacheService.evictRoom(roomId); // 참여자 변경 시 캐시 무효화
        }

        try {
            RoomResponse roomResponse = mapToRoomResponse(room);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    private RoomResponse mapToRoomResponse(Room room) {
        if (room == null) return null;

        // N+1 문제 해결: 참여자 ID 목록으로 한 번에 조회 (Bulk Read)
        List<User> participants = userRepository.findAllById(room.getParticipantIds());

        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(room.getId(), tenMinutesAgo);

        return new RoomResponse(
            room.getId(),
            room.getName() != null ? room.getName() : "제목 없음",
            room.isHasPassword(),
            participants.stream()
                .filter(p -> p != null && p.getId() != null)
                .map(p -> new UserResponse(
                    p.getId(),
                    p.getName() != null ? p.getName() : "알 수 없음",
                    p.getEmail() != null ? p.getEmail() : "",
                    p.getProfileImage() != null ? p.getProfileImage() : ""
                ))
                .collect(Collectors.toList()),
            room.getCreatedAt(),
            (int) recentMessageCount
        );
    }
}
