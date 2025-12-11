package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageRepository messageRepository;

    /**
     * 메시지 읽음 상태 업데이트
     * 최적화: saveAll()을 사용하여 벌크 업데이트 수행
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds.isEmpty()) {
            return;
        }

        Message.MessageReader readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        try {
            List<Message> messagesToUpdate = messageRepository.findAllById(messageIds);
            List<Message> changedMessages = new ArrayList<>(); // 실제로 변경된 메시지만 담을 리스트

            for (Message message : messagesToUpdate) {
                if (message.getReaders() == null) {
                    message.setReaders(new ArrayList<>());
                }
                boolean alreadyRead = message.getReaders().stream()
                        .anyMatch(r -> r.getUserId().equals(userId));

                if (!alreadyRead) {
                    message.getReaders().add(readerInfo);
                    changedMessages.add(message); // 변경된 메시지만 추가
                }
                // 여기서 save() 하지 않음!
            }

            // 변경된 게 있을 때만 한 번에 저장 (Bulk Write)
            if (!changedMessages.isEmpty()) {
                messageRepository.saveAll(changedMessages);
                log.debug("Read status updated for {} messages by user {}",
                        changedMessages.size(), userId);
            }

        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}