package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest (
		@NotBlank
		@Size(min = 2, max = 100)
		String name,
		
		@Size(min = 4, max = 100)
		String password
		) {
}
