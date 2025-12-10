package com.ktb.chatapp.dto;

import java.util.Map;
import java.util.Set;

public record MessageReactionResponse(
        String messageId,
        Map<String, Set<String>> reactions
) { }
