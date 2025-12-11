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
import java.util.*; // Set, Map, HashSet 등 추가
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

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String name) {

        try {
            // 정렬 설정 검증 및 Pageable 생성
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds";
            }

            PageRequest springPageRequest = PageRequest.of(
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    Sort.by(direction, sortField)
            );

            // 1. 방 목록 조회 (DB Query 1회)
            Page<Room> roomPage;
            if (pageRequest.getSearch() != null && !pageRequest.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                        pageRequest.getSearch().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            // ============ N+1 문제 해결 구간 시작 ============

            // 2. 모든 방에서 필요한 User ID 수집 (방장 + 참가자)
            Set<String> allUserIds = new HashSet<>();
            for (Room room : roomPage.getContent()) {
                if (room.getCreator() != null) {
                    allUserIds.add(room.getCreator());
                }
                if (room.getParticipantIds() != null) {
                    allUserIds.addAll(room.getParticipantIds());
                }
            }

            // 3. User 정보 한 번에 조회 (DB Query 1회)
            Map<String, User> userMap = Collections.emptyMap();
            if (!allUserIds.isEmpty()) {
                userMap = userRepository.findAllById(allUserIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
            }

            // 4. 조회한 User Map을 이용해 매핑 (DB 조회 없음)
            Map<String, User> finalUserMap = userMap;
            List<RoomResponse> roomResponses = roomPage.getContent().stream()
                    .map(room -> mapToRoomResponseWithCache(room, name, finalUserMap))
                    .collect(Collectors.toList());

            // ============ N+1 문제 해결 구간 끝 ============

            PageMetadata metadata = PageMetadata.builder()
                    .total(roomPage.getTotalElements())
                    .page(pageRequest.getPage())
                    .pageSize(pageRequest.getPageSize())
                    .totalPages(roomPage.getTotalPages())
                    .hasMore(roomPage.hasNext())
                    .currentCount(roomResponses.size())
                    .sort(PageMetadata.SortInfo.builder()
                            .field(pageRequest.getSortField())
                            .order(pageRequest.getSortOrder())
                            .build())
                    .build();

            return RoomsResponse.builder()
                    .success(true)
                    .data(roomResponses)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
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

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
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
        }

        try {
            RoomResponse roomResponse = mapToRoomResponse(room, name);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    // [최적화 버전] 미리 가져온 User Map을 사용 (DB 조회 X)
    private RoomResponse mapToRoomResponseWithCache(Room room, String requesterName, Map<String, User> userMap) {
        if (room == null) return null;

        User creator = null;
        if (room.getCreator() != null) {
            creator = userMap.get(room.getCreator());
        }

        List<User> participants = room.getParticipantIds().stream()
                .map(userMap::get)
                .filter(Objects::nonNull) // null 안전 처리
                .collect(Collectors.toList());

        // 메시지 카운트는 여전히 개별 조회 (MessageRepository 구조상 한계)
        // 만약 MessageRepository에 벌크 카운트 기능이 있다면 여기서도 최적화 가능
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(room.getId(), tenMinutesAgo);

        return buildRoomResponse(room, requesterName, creator, participants, recentMessageCount);
    }

    // [기존 버전] 단건 처리를 위해 유지 (User 직접 조회)
    private RoomResponse mapToRoomResponse(Room room, String requesterName) {
        if (room == null) return null;

        User creator = null;
        if (room.getCreator() != null) {
            creator = userRepository.findById(room.getCreator()).orElse(null);
        }

        List<User> participants = room.getParticipantIds().stream()
                .map(userRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(room.getId(), tenMinutesAgo);

        return buildRoomResponse(room, requesterName, creator, participants, recentMessageCount);
    }

    // 응답 객체 생성 로직 (공통화)
    private RoomResponse buildRoomResponse(Room room, String requesterName, User creator, List<User> participants, long recentMessageCount) {
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .build() : null)
                .participants(participants.stream()
                        .map(p -> UserResponse.builder()
                                .id(p.getId())
                                .name(p.getName() != null ? p.getName() : "알 수 없음")
                                .email(p.getEmail() != null ? p.getEmail() : "")
                                .build())
                        .collect(Collectors.toList()))
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getId() != null && creator.getEmail() != null && creator.getEmail().equals(requesterName)) // 이메일 비교로 수정 (name 파라미터가 email로 추정됨)
                .recentMessageCount((int) recentMessageCount)
                .build();
    }
}