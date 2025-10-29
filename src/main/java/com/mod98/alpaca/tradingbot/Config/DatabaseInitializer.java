package com.mod98.alpaca.tradingbot.Config;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component("databaseBootstrap")
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate tx;

    public DatabaseInitializer(PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public void init() {
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                CREATE TABLE IF NOT EXISTS app_settings (
                  id BIGINT PRIMARY KEY,
                  ai_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                  allow_forwarded BOOLEAN NOT NULL DEFAULT TRUE,
                  alpaca_extended_hours BOOLEAN NOT NULL DEFAULT TRUE,
                  alpaca_max_slippage_bps INTEGER NOT NULL DEFAULT 30,
                  alpaca_min_volume BIGINT NOT NULL DEFAULT 100000,
                  alpaca_paper BOOLEAN NOT NULL DEFAULT TRUE,
                  alpaca_spread_guard_bps INTEGER NOT NULL DEFAULT 50,
                  channel_id BIGINT,
                  fixed_budget NUMERIC(12,2) NOT NULL DEFAULT 200.00,
                  regex_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                  session_dir VARCHAR(255),
                  tp_percent NUMERIC(5,2) NOT NULL DEFAULT 5.00,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """).executeUpdate();

            entityManager.createNativeQuery("""
                INSERT INTO app_settings
                  (id, ai_enabled, allow_forwarded, alpaca_extended_hours, alpaca_max_slippage_bps,
                  alpaca_min_volume, alpaca_paper, alpaca_spread_guard_bps, channel_id,
                   fixed_budget, regex_enabled, session_dir, tp_percent, updated_at)
                VALUES (1, FALSE, TRUE, TRUE, 30, 100000, TRUE, 50, 0, 200.00, TRUE, 'sessions', 5.00, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

            log.info("✅ app_settings is ready with a default row.");
        });
    }
}
