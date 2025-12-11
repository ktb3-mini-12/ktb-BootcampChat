package com.ktb.chatapp.dto;

import java.util.List;

public record RoomsResponse(
        boolean success,
        List<RoomResponse> data,
        PageMetadata metadata
) { }
