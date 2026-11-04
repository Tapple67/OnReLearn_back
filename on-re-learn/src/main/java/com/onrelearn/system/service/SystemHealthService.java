package com.onrelearn.system.service;

import com.onrelearn.system.repository.DatabaseProbeRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthService {
    private final DatabaseProbeRepository repository;
    public SystemHealthService(DatabaseProbeRepository repository) { this.repository = repository; }
    public boolean isDatabaseReady() {
        try { return repository.isReachable(); }
        catch (DataAccessException exception) { return false; }
    }
}
