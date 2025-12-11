package com.ktb.chatapp.dto;

import java.util.Map;

public record ChatMessageRequest(
		String room,
		String type,
		String content,
		String msg,
		Map<String, Object> fileData
) {
    /**
     * Content 필드가 비어있으면 msg 필드를 반환하는 정규화된 content를 제공
     */
    public String getNormalizedContent() {
        if (content != null && !content.trim().isEmpty()) {
            return content;
        }
        return msg != null ? msg : "";
    }
    
    /**
     * 파싱된 메시지 내용 객체 반환 (AI 멘션 추출 포함)
     */
    public MessageContent getParsedContent() {
        return MessageContent.from(getNormalizedContent());
    }

    /**
     * 메시지 타입을 반환 (기본값: "text")
     */
    public String getMessageType() {
        return type != null ? type : "text";
    }

    /**
     * fileData가 존재하는지 확인
     */
    public boolean hasFileData() {
        return fileData != null && !fileData.isEmpty();
    }

    public String getRoom() {
        if (room == null || room.trim().isEmpty()) {
            throw new IllegalArgumentException("채팅방 정보가 없습니다.");
        }
        return room;
    }
}
