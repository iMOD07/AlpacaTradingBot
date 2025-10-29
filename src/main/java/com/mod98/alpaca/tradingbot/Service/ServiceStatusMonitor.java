package com.mod98.alpaca.tradingbot.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ServiceStatusMonitor {

    private final JdbcTemplate jdbc;

    @Value("${ALPACA_BASE_URL:#{null}}")
    private String alpacaUrl;

    public ServiceStatusMonitor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void checkAndUpdate() {
        try {
            // Check Alpaca API reachability
            boolean alpacaUp = pingAlpaca();
            jdbc.update("UPDATE service_status SET is_up=?, last_checked=now(), notes=? WHERE name='alpaca_api'",
                    alpacaUp, alpacaUp ? "✅ Connected" : "❌ Not reachable");

            // Check Telegram (if TD session exists)
            boolean telegramUp = new java.io.File("sessions").exists();
            jdbc.update("UPDATE service_status SET is_up=?, last_checked=now(), notes=? WHERE name='telegram_client'",
                    telegramUp, telegramUp ? "✅ Session found" : "❌ Session missing");

            System.out.println("✅ ServiceStatusMonitor updated table successfully.");
        } catch (Exception e) {
            System.err.println("⚠️ ServiceStatusMonitor failed: " + e.getMessage());
        }
    }

    private boolean pingAlpaca() {
        try {
            var conn = java.net.HttpURLConnection.class
                    .cast(new java.net.URL(alpacaUrl + "/v2/account").openConnection());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }
}
