package com.onrelearn.system.controller;

import com.onrelearn.system.service.SystemHealthService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
public class SystemHealthController {
    private final SystemHealthService service;
    public SystemHealthController(SystemHealthService service) { this.service = service; }
    @GetMapping("/api/system/health")
    public ResponseEntity<HealthResponse> health() {
        boolean ready = service.isDatabaseReady();
        return ResponseEntity.status(ready ? 200 : 503).body(new HealthResponse(ready ? "UP" : "DOWN"));
    }
    public record HealthResponse(String status) {}
}
