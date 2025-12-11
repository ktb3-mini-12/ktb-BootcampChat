package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 메시지 응답 DTO.
 */
public record MessageResponse(
        @JsonProperty("_id")
        String id,
        String content,
        UserResponse sender,
        MessageType type,
        @JsonProperty("file")
        FileResponse file,
        long timestamp,
        Map<String, Set<String>> reactions,
        List<Message.MessageReader> readers
) { }
