package com.onrelearn.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseProbeRepository {
    private final JdbcTemplate jdbc;
    public DatabaseProbeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean isReachable() { return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); }
}
