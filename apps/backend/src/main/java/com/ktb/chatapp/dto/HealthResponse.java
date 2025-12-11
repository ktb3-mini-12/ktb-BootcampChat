package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse (
		boolean isSuccess,
		String timestamp,
		Map<String, ServiceHealth> services,
		@JsonIgnore
		LocalDateTime lastActivity
) {
	
	public HealthResponse {
		timestamp = Instant.now().toString();
	}
	
    @JsonGetter("lastActivity")
    public String getLastActivity() {
        if (lastActivity == null) {
            return null;
        }
        return lastActivity.atZone(ZoneId.systemDefault())
                .toInstant()
                .toString();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceHealth {
        private boolean connected;
        private long latency;
    }
}
