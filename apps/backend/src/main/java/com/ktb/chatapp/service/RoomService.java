package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest) {

        try {
            String sortField = pageRequest.isValidSortField() ? pageRequest.sortField() : "createdAt";
            String sortOrder = pageRequest.isValidSortOrder() ? pageRequest.sortOrder() : "desc";

            // 정렬 방향 설정
            Sort.Direction direction = "desc".equals(sortOrder)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

            // Pageable 객체 생성
            PageRequest springPageRequest = PageRequest.of(
                pageRequest.page(),
                pageRequest.pageSize(),
                Sort.by(direction, sortField)
            );

            // 검색어가 있는 경우와 없는 경우 분리
            Page<Room> roomPage;
            if (pageRequest.search() != null && !pageRequest.search().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                    pageRequest.search().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            // Room을 RoomResponse로 변환
            List<RoomResponse> roomResponses = roomPage.getContent().stream()
                .map(this::mapToRoomResponse)
                .collect(Collectors.toList());

            // 메타데이터 생성
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

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
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
        
        // Publish event for room created
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

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        
        // Publish event for room updated
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

        List<User> participants = room.getParticipantIds().stream()
            .map(userRepository::findById)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();

        // 최근 10분간 메시지 수 조회
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
