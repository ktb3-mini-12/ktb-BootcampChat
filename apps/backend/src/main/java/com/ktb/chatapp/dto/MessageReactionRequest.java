package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;

public record MessageReactionRequest(
        @NotBlank
        String emoji,
        String messageId,
        String type, // "add" 또는 "remove"
        String reaction // emoji와 동일한 용도
) {
    // 호환성을 위한 getter 메서드
    public String getReaction() {
        return reaction != null ? reaction : emoji;
    }
}
