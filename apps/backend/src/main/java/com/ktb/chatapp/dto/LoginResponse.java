package com.ktb.chatapp.dto;

public record LoginResponse(
        boolean success,
        String token,
        String sessionId,
        AuthUserDto user,
        String message
) { }
