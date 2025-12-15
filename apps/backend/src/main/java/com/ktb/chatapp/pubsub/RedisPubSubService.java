package com.ktb.chatapp.pubsub;

import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

import com.ktb.chatapp.websocket.socketio.SocketUser;

import static com.ktb.chatapp.pubsub.RedisBroadcastMessage.*;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Redis Pub/Sub 서비스
 * WebSocket 서버 간 메시지 동기화를 담당
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class RedisPubSubService {

    private static final String TOPIC_NAME = "chat:broadcast";

    private final RedissonClient redissonClient;
    private final SocketIOServer socketIOServer;
    private final ObjectMapper objectMapper;
    private final String serverId;

    private RTopic topic;
    private int listenerId;

    public RedisPubSubService(
            RedissonClient redissonClient,
            SocketIOServer socketIOServer,
            ObjectMapper objectMapper,
            @Value("${server.id:#{T(java.util.UUID).randomUUID().toString()}}") String serverId
    ) {
        this.redissonClient = redissonClient;
        this.socketIOServer = socketIOServer;
        this.objectMapper = objectMapper;
        this.serverId = serverId;
    }

    @PostConstruct
    public void init() {
        // StringCodec 사용하여 @class 타입 정보 문제 우회
        topic = redissonClient.getTopic(TOPIC_NAME, StringCodec.INSTANCE);

        // String으로 수신 후 직접 역직렬화
        listenerId = topic.addListener(String.class, (channel, messageJson) -> {
            try {
                RedisBroadcastMessage message = objectMapper.readValue(messageJson, RedisBroadcastMessage.class);
                handleMessage(message);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize Redis message: {}", messageJson, e);
            }
        });

        log.info("Redis Pub/Sub initialized - serverId: {}, topic: {}", serverId, TOPIC_NAME);
    }

    @PreDestroy
    public void destroy() {
        if (topic != null) {
            topic.removeListener(listenerId);
            log.info("Redis Pub/Sub listener removed - serverId: {}", serverId);
        }
    }

    /**
     * 메시지를 Redis로 발행
     * 다른 모든 WebSocket 서버에 브로드캐스트됨
     */
    public void publish(String eventType, String roomId, Object data) {
        try {
            String payload = objectMapper.writeValueAsString(data);
            RedisBroadcastMessage message = new RedisBroadcastMessage(
                serverId, eventType, roomId, payload
            );

            // String으로 직렬화하여 발행 (StringCodec 사용)
            // 동기 발행으로 변경하여 메시지 전파 보장
            String messageJson = objectMapper.writeValueAsString(message);
            topic.publish(messageJson);
            log.debug("Published to Redis - eventType: {}, roomId: {}, serverId: {}",
                eventType, roomId, serverId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for Redis publish - eventType: {}, roomId: {}",
                eventType, roomId, e);
        }
    }

    /**
     * 특정 사용자들에게만 메시지 발행 (Selective Unicast)
     * O(N²) 브로드캐스트 대신 O(N) 타겟팅으로 성능 개선
     */
    public void publishToUsers(String eventType, String roomId, Set<String> targetUserIds, Object data) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return;
        }

        try {
            // targetUserIds를 payload에 포함
            Map<String, Object> wrappedData = Map.of(
                "data", data,
                "targetUserIds", targetUserIds
            );
            String payload = objectMapper.writeValueAsString(wrappedData);
            RedisBroadcastMessage message = new RedisBroadcastMessage(
                serverId, eventType, roomId, payload
            );

            String messageJson = objectMapper.writeValueAsString(message);
            topic.publish(messageJson);
            log.debug("Published to Redis (targeted) - eventType: {}, roomId: {}, targetUsers: {}",
                eventType, roomId, targetUserIds.size());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize targeted message for Redis publish - eventType: {}, roomId: {}",
                eventType, roomId, e);
        }
    }

    /**
     * Redis에서 수신한 메시지 처리
     * 자신이 발행한 메시지는 무시 (이미 로컬에서 처리됨)
     */
    private void handleMessage(RedisBroadcastMessage message) {
        // 자신이 발행한 메시지는 무시
        if (serverId.equals(message.originServerId())) {
            log.trace("Ignoring own message - eventType: {}, roomId: {}",
                message.eventType(), message.roomId());
            return;
        }

        log.debug("Received from Redis - eventType: {}, roomId: {}, originServerId: {}",
            message.eventType(), message.roomId(), message.originServerId());

        try {
            String socketEvent = mapToSocketEvent(message.eventType());

            // 특수 케이스: ROOM_CREATED는 room-list에 브로드캐스트
            if (EVENT_ROOM_CREATED.equals(message.eventType())) {
                Object payload = deserializePayload(message.payload());
                socketIOServer.getRoomOperations("room-list")
                    .sendEvent(socketEvent, payload);
            }
            // Selective Unicast: MESSAGES_READ는 타겟 사용자에게만 전송
            else if (EVENT_MESSAGES_READ.equals(message.eventType())) {
                handleTargetedMessage(message, socketEvent);
            }
            else {
                Object payload = deserializePayload(message.payload());
                socketIOServer.getRoomOperations(message.roomId())
                    .sendEvent(socketEvent, payload);
            }

            log.debug("Broadcasted to local clients - event: {}, roomId: {}",
                socketEvent, message.roomId());
        } catch (Exception e) {
            log.error("Failed to handle Redis message - eventType: {}, roomId: {}",
                message.eventType(), message.roomId(), e);
        }
    }

    /**
     * 타겟팅된 메시지 처리 (Selective Unicast)
     * targetUserIds가 있으면 해당 사용자에게만 전송
     */
    private void handleTargetedMessage(RedisBroadcastMessage message, String socketEvent) {
        try {
            Map<String, Object> wrappedPayload = objectMapper.readValue(
                message.payload(), new TypeReference<Map<String, Object>>() {});

            Object targetUserIdsObj = wrappedPayload.get("targetUserIds");
            Object actualData = wrappedPayload.get("data");

            if (targetUserIdsObj instanceof java.util.List<?> targetList && actualData != null) {
                Set<String> targetUserIds = new java.util.HashSet<>();
                for (Object id : targetList) {
                    if (id != null) {
                        targetUserIds.add(id.toString());
                    }
                }

                // 타겟 사용자에게만 전송
                socketIOServer.getRoomOperations(message.roomId()).getClients().stream()
                    .filter(client -> {
                        SocketUser user = (SocketUser) client.get("user");
                        return user != null && targetUserIds.contains(user.id());
                    })
                    .forEach(client -> client.sendEvent(socketEvent, actualData));

                log.debug("Sent targeted message to {} users in room {}",
                    targetUserIds.size(), message.roomId());
            } else {
                // targetUserIds가 없으면 일반 브로드캐스트
                Object payload = deserializePayload(message.payload());
                socketIOServer.getRoomOperations(message.roomId())
                    .sendEvent(socketEvent, payload);
            }
        } catch (Exception e) {
            log.error("Failed to handle targeted message - eventType: {}, roomId: {}",
                message.eventType(), message.roomId(), e);
        }
    }

    /**
     * Redis 이벤트 타입을 Socket.IO 이벤트로 매핑
     */
    private String mapToSocketEvent(String eventType) {
        return switch (eventType) {
            case EVENT_MESSAGE -> MESSAGE;
            case EVENT_AI_START -> AI_MESSAGE_START;
            case EVENT_AI_CHUNK -> AI_MESSAGE_CHUNK;
            case EVENT_AI_COMPLETE -> AI_MESSAGE_COMPLETE;
            case EVENT_AI_ERROR -> AI_MESSAGE_ERROR;
            case EVENT_ROOM_JOIN -> MESSAGE;  // 입장 메시지는 MESSAGE 이벤트로
            case EVENT_ROOM_LEAVE -> MESSAGE;  // 퇴장 메시지는 MESSAGE 이벤트로
            case EVENT_USER_LEFT -> USER_LEFT;
            case EVENT_PARTICIPANTS_UPDATE -> PARTICIPANTS_UPDATE;
            case EVENT_ROOM_CREATED -> ROOM_CREATED;
            case EVENT_ROOM_UPDATE -> ROOM_UPDATE;
            case EVENT_MESSAGE_REACTION_UPDATE -> MESSAGE_REACTION_UPDATE;
            case EVENT_MESSAGES_READ -> MESSAGES_READ;
            default -> {
                log.warn("Unknown event type: {}", eventType);
                yield eventType;
            }
        };
    }

    /**
     * JSON payload를 역직렬화 (Map 또는 List)
     */
    private Object deserializePayload(String payload) throws JsonProcessingException {
        if (payload == null || payload.isBlank()) {
            log.warn("Empty or null payload received, returning empty map");
            return Map.of();
        }

        String trimmed = payload.trim();
        if (trimmed.startsWith("[")) {
            // List 타입 (예: participants)
            return objectMapper.readValue(trimmed, new TypeReference<java.util.List<Map<String, Object>>>() {});
        } else {
            // Map 타입 (예: message)
            return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
        }
    }

    /**
     * 현재 서버 ID 반환
     */
    public String getServerId() {
        return serverId;
    }
}
