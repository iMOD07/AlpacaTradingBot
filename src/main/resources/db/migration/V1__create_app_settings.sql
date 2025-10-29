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
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 1 initial class (Sivk from "app_settings not initialized")
INSERT INTO app_settings (id) VALUES (1)
ON CONFLICT (id) DO NOTHING;
