package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "채팅방 응답 정보")
public record RoomResponse(
        @Schema(description = "채팅방 ID", example = "60d5ec49f1b2c8b9e8c4f2a1")
        @JsonProperty("_id")
        String id,
        @Schema(description = "채팅방 이름", example = "프로젝트 논의방")
        String name,
        @Schema(description = "비밀번호 설정 여부", example = "false")
        boolean hasPassword,
        @Schema(description = "참여자 목록")
        List<UserResponse> participants,
        @JsonIgnore
        LocalDateTime createdAtDateTime,
        @Schema(description = "최근 10분간 메시지 수", example = "23")
        Integer recentMessageCount
) {

    @Schema(description = "채팅방 생성 시간 (ISO 8601 형식)", example = "2025-11-18T12:34:56.789Z")
    @JsonGetter("createdAt")
    public String getCreatedAt() {
        if (createdAtDateTime == null) {
            return null;
        }
        return createdAtDateTime
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toString();
    }
}
