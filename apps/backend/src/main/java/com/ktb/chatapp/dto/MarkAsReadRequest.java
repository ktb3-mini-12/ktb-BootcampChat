package com.ktb.chatapp.dto;

import java.util.List;

public record MarkAsReadRequest (
		String roomId,
		List<String> messageIds
){ }
