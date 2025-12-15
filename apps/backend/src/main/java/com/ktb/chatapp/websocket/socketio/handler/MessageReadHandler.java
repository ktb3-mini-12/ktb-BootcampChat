package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.CacheService;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.pubsub.RedisBroadcastMessage;
import com.ktb.chatapp.pubsub.RedisPubSubService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final CacheService cacheService;
    private final RedisPubSubService redisPubSubService;
    
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String readerId = getUserId(client);
            if (readerId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.messageIds() == null || data.messageIds().isEmpty()) {
                return;
            }

            // 메시지 조회 및 발신자 ID 수집 (Selective Unicast용)
            List<Message> messages = messageRepository.findAllById(data.messageIds());
            Set<String> senderIds = new HashSet<>();
            String roomId = null;

            for (Message message : messages) {
                if (message.getSenderId() != null) {
                    senderIds.add(message.getSenderId());
                }
                if (roomId == null && message.getRoomId() != null) {
                    roomId = message.getRoomId();
                }
            }

            // roomId 결정: DB에서 찾은 roomId 또는 request의 roomId 사용 (Race Condition 대응)
            if (roomId == null && data.roomId() != null && !data.roomId().isBlank()) {
                roomId = data.roomId();
                log.debug("Message not in DB yet, using client roomId - messageIds: {}, roomId: {}",
                        data.messageIds(), roomId);
            }

            if (roomId == null) {
                log.debug("Cannot determine roomId for read status update - messageIds: {}", data.messageIds());
                return;
            }

            // P1: CacheService로 User/Room 존재 여부 확인 (DB 부하 감소)
            if (cacheService.findUserById(readerId).isEmpty()) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            Room room = cacheService.findRoomById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(readerId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            messageReadStatusService.updateReadStatus(data.messageIds(), readerId);

            MessagesReadResponse response = new MessagesReadResponse(readerId, data.messageIds());

            // Option B: Selective Unicast - 메시지 발신자에게만 전송 (O(N²) → O(N))
            sendToMessageSenders(roomId, senderIds, response);

            // Redis Pub/Sub으로 다른 서버의 발신자에게 전송
            redisPubSubService.publishToUsers(
                    RedisBroadcastMessage.EVENT_MESSAGES_READ,
                    roomId,
                    senderIds,
                    response
            );

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }

    /**
     * Selective Unicast: 메시지 발신자에게만 읽음 상태 전송
     * Room 전체 브로드캐스트 대신 발신자만 타겟팅하여 O(N²) → O(N) 복잡도 개선
     */
    private void sendToMessageSenders(String roomId, Set<String> senderIds, MessagesReadResponse response) {
        if (senderIds.isEmpty()) {
            return;
        }

        socketIOServer.getRoomOperations(roomId).getClients().stream()
                .filter(c -> {
                    SocketUser user = (SocketUser) c.get("user");
                    return user != null && senderIds.contains(user.id());
                })
                .forEach(c -> c.sendEvent(MESSAGES_READ, response));
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }
}
