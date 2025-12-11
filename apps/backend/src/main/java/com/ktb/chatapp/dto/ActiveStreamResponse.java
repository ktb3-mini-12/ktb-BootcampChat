package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Active AI streaming session 응답 DTO.
 */
public record ActiveStreamResponse (
	@JsonProperty("_id")
	 String id,
	 String type,
	 String aiType,
	 String content,
	 String timestamp,  // ISO_INSTANT 형식 문자열 예) 2025-11-07T13:45:30Z
	 boolean isStreaming
) {}