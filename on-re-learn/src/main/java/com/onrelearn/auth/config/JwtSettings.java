package com.onrelearn.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "onrelearn.auth.jwt")
public record JwtSettings(String secret, Duration ttl) {}
