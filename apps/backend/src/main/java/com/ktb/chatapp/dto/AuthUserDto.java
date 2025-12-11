package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthUserDto (
    @JsonProperty("_id")
    String id,
    String name,
    String email,
    String profileImage
) {}
