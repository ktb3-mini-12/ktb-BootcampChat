package com.ktb.chatapp.dto;

import java.util.List;

public record MessagesReadResponse(
        String userId,
        List<String> messageIds
) { }
