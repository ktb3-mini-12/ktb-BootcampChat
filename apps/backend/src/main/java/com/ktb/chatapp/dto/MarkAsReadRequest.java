package com.ktb.chatapp.dto;

import java.util.List;

public record MarkAsReadRequest (
		List<String> messageIds
){ }
