package com.mod98.alpaca.tradingbot.Controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ServiceStatusController {

    private final JdbcTemplate jdbc;

    public ServiceStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/status")
    public List<Map<String, Object>> getAllStatuses() {
        return jdbc.queryForList("SELECT name, is_up, last_checked, notes FROM service_status ORDER BY name");
    }
}
