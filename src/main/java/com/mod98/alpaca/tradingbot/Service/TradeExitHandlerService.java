package com.mod98.alpaca.tradingbot.Service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;

@Service
public class TradeExitHandlerService {

    private static final Logger log = LoggerFactory.getLogger(TradeExitHandlerService.class);

    private final AlpacaClient alpaca;
    private final TradeRecordService records;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, Boolean> seen = new ConcurrentHashMap<>();

    private static final int LOOKBACK_MINUTES = 60;

    public TradeExitHandlerService(AlpacaClient alpaca, TradeRecordService records) {
        this.alpaca = alpaca;
        this.records = records;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TradeExitWatcher");
            t.setDaemon(true);
            return t;
        });
        start();
    }

    private void start() {
        scheduler.scheduleAtFixedRate(this::pollClosedSellOrders, 5, 10, TimeUnit.SECONDS);
        log.info("📊 TradeExitHandlerService started (poll 10s, lookback={}m)", LOOKBACK_MINUTES);
    }

    private void pollClosedSellOrders() {
        try {
            Instant since = Instant.now().minusSeconds(LOOKBACK_MINUTES * 60L);
            JsonNode arr = alpaca.listOrders("closed", "sell", since, 100);
            if (arr == null || !arr.isArray()) return;

            for (JsonNode ord : arr) {
                String id = ord.path("id").asText(null);
                if (id == null || id.isBlank() || seen.putIfAbsent(id, true) != null) continue;

                String symbol = ord.path("symbol").asText(null);
                String type = ord.path("type").asText("").toLowerCase();         // limit / stop
                String side = ord.path("side").asText("").toLowerCase();         // sell
                String filledAvg = ord.path("filled_avg_price").asText("");
                if (!"sell".equals(side) || symbol == null || symbol.isBlank() || filledAvg.isBlank()) continue;

                BigDecimal exitPrice;
                try { exitPrice = new BigDecimal(filledAvg); } catch (Exception e) { continue; }

                String reason = switch (type) {
                    case "limit" -> "TP";
                    case "stop", "stop_limit", "stop_limit_order" -> "SL";
                    default -> "TP";
                };

                records.recordExit(symbol, exitPrice, reason);
                log.info("✅ Exit recorded from Alpaca: {} {} @ {} (orderId={})", symbol, reason, exitPrice, id);
            }

        } catch (Exception e) {
            log.error("Exit poll failed: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Stopping TradeExitHandlerService...");
        scheduler.shutdownNow();
    }
}
