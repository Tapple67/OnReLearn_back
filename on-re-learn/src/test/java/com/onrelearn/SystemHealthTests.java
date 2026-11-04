package com.onrelearn;

import com.onrelearn.system.controller.SystemHealthController;
import com.onrelearn.system.repository.DatabaseProbeRepository;
import com.onrelearn.system.service.SystemHealthService;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemHealthTests {
    @Test
    void returnsReadyAfterDatabaseProbe() {
        DatabaseProbeRepository repository = mock(DatabaseProbeRepository.class);
        when(repository.isReachable()).thenReturn(true);
        var response = new SystemHealthController(new SystemHealthService(repository)).health();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().status());
    }
    @Test
    void databaseFailureDoesNotExposeConnectionDetails() {
        DatabaseProbeRepository repository = mock(DatabaseProbeRepository.class);
        when(repository.isReachable()).thenThrow(new DataAccessResourceFailureException("sensitive-connection-detail"));
        var response = new SystemHealthController(new SystemHealthService(repository)).health();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().status());
    }

    @Test
    void refusedJdbcConnectionReturnsHttp503() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        var dataSource = new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:" + unusedPort + "/onrelearn?connectTimeout=1000", "unused", "unused");
        var controller = new SystemHealthController(
            new SystemHealthService(new DatabaseProbeRepository(new JdbcTemplate(dataSource))));

        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/api/system/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"status\":\"DOWN\"}"));
    }
}
