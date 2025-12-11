package com.ktb.chatapp.dto;

import java.util.List;

public record FetchMessagesResponse (
		List<MessageResponse> messages,
		boolean hasMore
) {
    
    public long firstMessageTimestamp() {
        return messages.getFirst().timestamp();
    }
}
