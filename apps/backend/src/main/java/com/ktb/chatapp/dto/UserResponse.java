package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.User;

public record UserResponse(
        String id,
        String name,
        String email,
        String profileImage
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getProfileImage() != null ? user.getProfileImage() : ""
        );
    }
}
