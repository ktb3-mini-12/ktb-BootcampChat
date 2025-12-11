package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메시지를 응답 DTO로 변환하는 매퍼
 * 파일 정보, 사용자 정보 등을 포함한 MessageResponse 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    private final FileRepository fileRepository;

    /**
     * Message 엔티티를 MessageResponse DTO로 변환
     *
     * @param message 변환할 메시지 엔티티
     * @param sender 메시지 발신자 정보 (null 가능)
     * @return MessageResponse DTO
     */
    public MessageResponse mapToMessageResponse(Message message, User sender) {
        UserResponse senderResponse = null;
        if (sender != null) {
            senderResponse = new UserResponse(
                    sender.getId(),
                    sender.getName(),
                    sender.getEmail(),
                    sender.getProfileImage()
            );
        }

        // 파일 정보 설정
        FileResponse fileResponse = Optional.ofNullable(message.getFileId())
                .flatMap(fileRepository::findById)
                .map(file -> new FileResponse(file.getFilename(), file.getOriginalname(), file.getMimetype(), file.getSize()))
                .orElse(null);

        return new MessageResponse(
                message.getId(),
                message.getContent(),
                senderResponse,
                message.getType(),
                fileResponse,
                message.toTimestampMillis(),
                message.getReactions() != null ? message.getReactions() : new HashMap<>(),
                message.getReaders() != null ? message.getReaders() : new ArrayList<>()
        );
    }
}
