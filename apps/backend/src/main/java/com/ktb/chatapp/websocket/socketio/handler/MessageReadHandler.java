package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.pubsub.RedisBroadcastMessage;
import com.ktb.chatapp.pubsub.RedisPubSubService;
import java.util.Map;
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
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RedisPubSubService redisPubSubService;
    
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.messageIds() == null || data.messageIds().isEmpty()) {
                return;
            }

            // 메시지에서 roomId 조회 (신뢰할 수 있는 소스)
            String messageRoomId = messageRepository.findById(data.messageIds().getFirst())
                    .map(Message::getRoomId).orElse(null);

            // roomId 결정: 메시지가 DB에 있으면 메시지의 roomId 사용, 없으면 request의 roomId 사용
            // (메시지 브로드캐스트 후 비동기 저장으로 인한 Race Condition 대응)
            String roomId;
            if (messageRoomId != null && !messageRoomId.isBlank()) {
                // 메시지가 DB에 있는 경우: 메시지의 roomId를 신뢰
                roomId = messageRoomId;

                // 보안 검증: 클라이언트가 보낸 roomId와 메시지의 실제 roomId가 일치하는지 확인
                if (data.roomId() != null && !data.roomId().isBlank()
                        && !data.roomId().equals(messageRoomId)) {
                    log.warn("Room ID mismatch - client sent: {}, message belongs to: {}, userId: {}",
                            data.roomId(), messageRoomId, userId);
                    client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                    return;
                }
            } else if (data.roomId() != null && !data.roomId().isBlank()) {
                // 메시지가 DB에 없는 경우 (Race Condition): request의 roomId 사용
                // 이 경우 room 접근 권한 검증으로 보안 확보
                roomId = data.roomId();
                log.debug("Message not in DB yet, using client roomId - messageIds: {}, roomId: {}",
                        data.messageIds(), roomId);
            } else {
                // roomId를 알 수 없는 경우 조용히 무시
                log.debug("Cannot determine roomId for read status update - messageIds: {}", data.messageIds());
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }
            
            messageReadStatusService.updateReadStatus(data.messageIds(), userId);

            MessagesReadResponse response = new MessagesReadResponse(userId, data.messageIds());

            // Broadcast to room
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGES_READ, response);

            // Redis Pub/Sub으로 다른 서버에 읽음 상태 업데이트 브로드캐스트
            redisPubSubService.publish(
                    RedisBroadcastMessage.EVENT_MESSAGES_READ,
                    roomId,
                    response
            );

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }
}
