package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.*;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import com.ktb.chatapp.service.CacheService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatMessageHandler {
	private final SocketIOServer socketIOServer;
	private final CacheService cacheService;
	private final FileRepository fileRepository;
	private final AiService aiService;
	private final SessionService sessionService;
	private final BannedWordChecker bannedWordChecker;
	private final RateLimitService rateLimitService;
	private final MeterRegistry meterRegistry;
	private final MessageRepository messageRepository;
	
	// Metrics 캐시 (매번 등록하지 않고 재사용)
	private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
	private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
	
	@OnEvent(CHAT_MESSAGE)
	public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {
		Timer.Sample timerSample = Timer.start(meterRegistry);
		
		if (data == null) {
			recordError("null_data");
			client.sendEvent(ERROR, Map.of(
					"code", "MESSAGE_ERROR",
					"message", "메시지 데이터가 없습니다."
			));
			timerSample.stop(createTimer("error", "null_data"));
			return;
		}
		
		var socketUser = (SocketUser) client.get("user");
		
		if (socketUser == null) {
			recordError("session_null");
			client.sendEvent(ERROR, Map.of(
					"code", "SESSION_EXPIRED",
					"message", "세션이 만료되었습니다. 다시 로그인해주세요."
			));
			timerSample.stop(createTimer("error", "session_null"));
			return;
		}
		
		SessionValidationResult validation =
				sessionService.validateSession(socketUser.id(), socketUser.authSessionId());
		if (!validation.isValid()) {
			recordError("session_expired");
			client.sendEvent(ERROR, Map.of(
					"code", "SESSION_EXPIRED",
					"message", "세션이 만료되었습니다. 다시 로그인해주세요."
			));
			timerSample.stop(createTimer("error", "session_expired"));
			return;
		}
		
		// Rate limit check
		RateLimitCheckResult rateLimitResult =
				rateLimitService.checkRateLimit(socketUser.id(), 10000, Duration.ofMinutes(1));
		if (!rateLimitResult.allowed()) {
			recordError("rate_limit_exceeded");
			Counter.builder("socketio.messages.rate_limit")
					.description("Socket.IO rate limit exceeded count")
					.register(meterRegistry)
					.increment();
			client.sendEvent(ERROR, Map.of(
					"code", "RATE_LIMIT_EXCEEDED",
					"message", "메시지 전송 횟수 제한을 초과했습니다. 잠시 후 다시 시도해주세요.",
					"retryAfter", rateLimitResult.retryAfterSeconds()
			));
			log.warn("Rate limit exceeded for user: {}, retryAfter: {}s",
					socketUser.id(), rateLimitResult.retryAfterSeconds());
			timerSample.stop(createTimer("error", "rate_limit"));
			return;
		}
		
		try {
			// 캐시 서비스를 통한 User 조회 (DB 부하 감소)
			User sender = cacheService.findUserById(socketUser.id()).orElse(null);
			if (sender == null) {
				recordError("user_not_found");
				client.sendEvent(ERROR, Map.of(
						"code", "MESSAGE_ERROR",
						"message", "User not found"
				));
				timerSample.stop(createTimer("error", "user_not_found"));
				return;
			}
			
			String roomId = data.getRoom();
			// 캐시 서비스를 통한 Room 조회 (DB 부하 감소)
			Room room = cacheService.findRoomById(roomId).orElse(null);
			if (room == null || !room.getParticipantIds().contains(socketUser.id())) {
				recordError("room_access_denied");
				client.sendEvent(ERROR, Map.of(
						"code", "MESSAGE_ERROR",
						"message", "채팅방 접근 권한이 없습니다."
				));
				timerSample.stop(createTimer("error", "room_access_denied"));
				return;
			}
			
			MessageContent messageContent = data.getParsedContent();
			
			log.debug("Message received - type: {}, room: {}, userId: {}, hasFileData: {}",
					data.getMessageType(), roomId, socketUser.id(), data.hasFileData());
			
			if (bannedWordChecker.containsBannedWord(messageContent.getTrimmedContent())) {
				recordError("banned_word");
				client.sendEvent(ERROR, Map.of(
						"code", "MESSAGE_REJECTED",
						"message", "금칙어가 포함된 메시지는 전송할 수 없습니다."
				));
				timerSample.stop(createTimer("error", "banned_word"));
				return;
			}
			
			String messageType = data.getMessageType();
			Message message = switch (messageType) {
				case "file" -> handleFileMessage(roomId, socketUser.id(), messageContent, data.fileData());
				case "text" -> handleTextMessage(roomId, socketUser.id(), messageContent);
				default -> throw new IllegalArgumentException("Unsupported message type: " + messageType);
			};
			
			if (message == null) {
				log.warn("Empty message - ignoring. room: {}, userId: {}, messageType: {}", roomId, socketUser.id(), messageType);
				timerSample.stop(createTimer("ignored", messageType));
				return;
			}
			
			// 브로드캐스트 먼저 실행 (실시간 응답 보장)
			socketIOServer.getRoomOperations(roomId)
					.sendEvent(MESSAGE, createMessageResponse(message, sender));
			
			// MongoDB에 비동기 저장 (Virtual Thread가 처리)
			CompletableFuture.runAsync(() -> {
				try {
					messageRepository.save(message);
				} catch (Exception e) {
					log.error("Failed to save message asynchronously - messageId: {}, roomId: {}",
							message.getId(), roomId, e);
				}
			});
			
			// AI 멘션 처리 (이미 비동기)
			aiService.handleAIMentions(roomId, socketUser.id(), messageContent);
			
			// 세션 활동 업데이트 (비동기)
			CompletableFuture.runAsync(() -> sessionService.updateLastActivity(socketUser.id()));
			
			// Record success metrics
			recordMessageSuccess(messageType);
			timerSample.stop(createTimer("success", messageType));
			
			log.debug("Message processed - type: {}, room: {}",
					message.getType(), roomId);
			
		} catch (Exception e) {
			recordError("exception");
			log.error("Message handling error", e);
			client.sendEvent(ERROR, Map.of(
					"code", "MESSAGE_ERROR",
					"message", e.getMessage() != null ? e.getMessage() : "메시지 전송 중 오류가 발생했습니다."
			));
			timerSample.stop(createTimer("error", "exception"));
		}
	}
	
	private Message handleFileMessage(String roomId, String userId, MessageContent messageContent, Map<String, Object> fileData) {
		if (fileData == null || fileData.get("_id") == null) {
			throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
		}
		
		String fileId = (String) fileData.get("_id");
		File file = fileRepository.findById(fileId).orElse(null);
		
		if (file == null || !file.getUser().equals(userId)) {
			throw new IllegalStateException("파일을 찾을 수 없거나 접근 권한이 없습니다.");
		}
		
		Message message = new Message();
		message.setId(UUID.randomUUID().toString());
		message.setRoomId(roomId);
		message.setSenderId(userId);
		message.setType(MessageType.file);
		message.setFileId(fileId);
		message.setContent(messageContent.getTrimmedContent());
		message.setTimestamp(LocalDateTime.now());
		message.setMentions(messageContent.aiMentions());
		
		// 메타데이터는 Map<String, Object>
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("fileType", file.getMimetype());
		metadata.put("fileSize", file.getSize());
		metadata.put("originalName", file.getOriginalname());
		metadata.put("fileName", file.getFilename());
		message.setMetadata(metadata);
		
		return message;
	}
	
	private Message handleTextMessage(String roomId, String userId, MessageContent messageContent) {
		if (messageContent.isEmpty()) {
			return null; // 빈 메시지는 무시
		}
		
		Message message = new Message();
		message.setId(UUID.randomUUID().toString());
		message.setRoomId(roomId);
		message.setSenderId(userId);
		message.setContent(messageContent.getTrimmedContent());
		message.setType(MessageType.text);
		message.setTimestamp(LocalDateTime.now());
		message.setMentions(messageContent.aiMentions());
		
		return message;
	}
	
	private MessageResponse createMessageResponse(Message message, User sender) {
		FileResponse fileResponse = buildFileResponse(message);
		
		return new MessageResponse(
				message.getId(),
				message.getContent(),
				UserResponse.from(sender),
				message.getType(),
				fileResponse,
				message.toTimestampMillis(),
				message.getReactions() != null ? message.getReactions() : Collections.emptyMap(),
				message.getReaders() != null ? message.getReaders() : Collections.emptyList()
		);
	}
	
	// Metrics helper methods (캐싱으로 매번 등록하지 않음)
	private Timer createTimer(String status, String messageType) {
		String key = "timer:" + status + ":" + messageType;
		return timerCache.computeIfAbsent(key, k ->
				Timer.builder("socketio.messages.processing.time")
						.description("Socket.IO message processing time")
						.tag("status", status)
						.tag("message_type", messageType)
						.register(meterRegistry)
		);
	}
	
	private void recordMessageSuccess(String messageType) {
		String key = "success:" + messageType;
		counterCache.computeIfAbsent(key, k ->
				Counter.builder("socketio.messages.total")
						.description("Total Socket.IO messages processed")
						.tag("status", "success")
						.tag("message_type", messageType)
						.register(meterRegistry)
		).increment();
	}
	
	private void recordError(String errorType) {
		String key = "error:" + errorType;
		counterCache.computeIfAbsent(key, k ->
				Counter.builder("socketio.messages.errors")
						.description("Socket.IO message processing errors")
						.tag("error_type", errorType)
						.register(meterRegistry)
		).increment();
	}
	
	private FileResponse buildFileResponse(Message message) {
		if (message.getFileId() == null) {
			return null;
		}
		
		Map<String, Object> metadata = message.getMetadata();
		if (metadata != null) {
			Object sizeObj = metadata.get("fileSize");
			Long size = null;
			if (sizeObj instanceof Number number) {
				size = number.longValue();
			}
			
			String filename = metadata.get("fileName") instanceof String fn ? fn : null;
			String originalName = metadata.get("originalName") instanceof String on ? on : null;
			String fileType = metadata.get("fileType") instanceof String ft ? ft : null;
			
			if (filename != null && originalName != null && fileType != null && size != null) {
				return new FileResponse(filename, originalName, fileType, size);
			}
		}
		
		return fileRepository.findById(message.getFileId())
				.map(FileResponse::from)
				.orElse(null);
	}
}