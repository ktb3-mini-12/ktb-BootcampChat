package com.ktb.chatapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RedisConfig {
	@Value("${spring.data.redis.host:localhost}")
	private String host;
	
	@Value("${spring.data.redis.port:6379}")
	private int port;
	
	@Bean
	@ConditionalOnProperty(name = "redis.enabled", havingValue = "true", matchIfMissing = true)
	public RedissonClient redissonClient() {

		Config config = new Config();
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		config.setCodec(new JsonJacksonCodec(mapper));
		
		config.useSingleServer()
				.setAddress(String.format("redis://%s:%d", host, port))
				.setConnectionPoolSize(1024)
				.setConnectionMinimumIdleSize(100)
				.setConnectTimeout(10000)
				.setTimeout(3000); 
		
		try {
			RedissonClient client = Redisson.create(config);
			log.info("Redisson client initialized with PoolSize 1024: {}:{}", host, port);
			return client;
		} catch (RedisConnectionException e) {
			log.error("Failed to initialize Redisson client: {}", e.getMessage(), e);
			throw e;
		}
	}
}