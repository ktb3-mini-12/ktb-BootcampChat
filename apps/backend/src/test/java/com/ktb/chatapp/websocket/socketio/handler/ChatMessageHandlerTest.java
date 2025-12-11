package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.BroadcastOperations;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RateLimitCheckResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileRepository fileRepository;
    @Mock private AiService aiService;
    @Mock private SessionService sessionService;
    @Mock private BannedWordChecker bannedWordChecker;
    @Mock private RateLimitService rateLimitService;
    @Mock private MessageRepository messageRepository;

    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations broadcastOperations;
    @Mock private AckRequest ackRequest;

    private MeterRegistry meterRegistry;
    private ChatMessageHandler chatMessageHandler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        chatMessageHandler = new ChatMessageHandler(
                socketIOServer, roomRepository, userRepository, fileRepository,
                aiService, sessionService, bannedWordChecker, rateLimitService,
                meterRegistry, messageRepository
        );
    }

    @Test
    @DisplayName("정상적인 텍스트 메시지 전송 테스트")
    void handleTextMessage_Success() {
        // given
        String roomId = "room1";
        String userId = "user1";
        String content = "Hello World";

        ChatMessageRequest request = new ChatMessageRequest(roomId, "text", content, null, null);

        SocketUser socketUser = new SocketUser(userId, "Tester", "test@example.com", "session1");

        User user = new User();
        user.setId(userId);
        user.setName("Tester");

        Room room = new Room();
        room.setId(roomId);
        room.setParticipantIds(Set.of(userId));

        when(client.get("user")).thenReturn(socketUser);

        SessionValidationResult validResult = mock(SessionValidationResult.class);
        when(validResult.isValid()).thenReturn(true);
        when(sessionService.validateSession(anyString(), anyString())).thenReturn(validResult);

        RateLimitCheckResult limitResult = new RateLimitCheckResult(true, 10, 10, 0, 0, 1);
        when(rateLimitService.checkRateLimit(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(limitResult);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bannedWordChecker.containsBannedWord(anyString())).thenReturn(false);

        when(socketIOServer.getRoomOperations(roomId)).thenReturn(broadcastOperations);

        // when
        chatMessageHandler.handleChatMessage(client, request);

        // then
        verify(broadcastOperations).sendEvent(eq("message"), any(MessageResponse.class));
        verify(messageRepository).save(any());
    }
}
