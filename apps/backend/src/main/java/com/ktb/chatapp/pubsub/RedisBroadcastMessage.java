package com.ktb.chatapp.pubsub;

import java.io.Serializable;

/**
 * Redis Pub/Sub을 통해 서버 간 전송되는 브로드캐스트 메시지
 * 모든 WebSocket 서버가 이 메시지를 구독하여 클라이언트에게 전달
 */
public record RedisBroadcastMessage(
    String originServerId,    // 메시지를 발행한 서버 ID (중복 처리 방지용)
    String eventType,         // 이벤트 타입 (MESSAGE, AI_CHUNK, AI_COMPLETE 등)
    String roomId,            // 대상 채팅방 ID
    String payload            // JSON 직렬화된 이벤트 데이터
) implements Serializable {

    // 이벤트 타입 상수
    public static final String EVENT_MESSAGE = "MESSAGE";
    public static final String EVENT_AI_START = "AI_START";
    public static final String EVENT_AI_CHUNK = "AI_CHUNK";
    public static final String EVENT_AI_COMPLETE = "AI_COMPLETE";
    public static final String EVENT_AI_ERROR = "AI_ERROR";
    public static final String EVENT_ROOM_JOIN = "ROOM_JOIN";
    public static final String EVENT_ROOM_LEAVE = "ROOM_LEAVE";
    public static final String EVENT_USER_LEFT = "USER_LEFT";
    public static final String EVENT_PARTICIPANTS_UPDATE = "PARTICIPANTS_UPDATE";
    public static final String EVENT_ROOM_CREATED = "ROOM_CREATED";
    public static final String EVENT_ROOM_UPDATE = "ROOM_UPDATE";
    public static final String EVENT_MESSAGE_REACTION_UPDATE = "MESSAGE_REACTION_UPDATE";
    public static final String EVENT_MESSAGES_READ = "MESSAGES_READ";
}
