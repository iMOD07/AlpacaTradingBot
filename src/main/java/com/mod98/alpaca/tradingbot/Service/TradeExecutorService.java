package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Parsing.TradeSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

@RequiredArgsConstructor
@Service
public class TradeExecutorService {

    private final AlpacaClient alpaca;
    private final PriceWatcherService watcher;
    private final TradeAuditService audit;

    public void executeSignal(TradeSignal sig, int qty, BigDecimal tpPercent, boolean extendedHours) {
        // 1-Arming record
        audit.record(sig.symbol(), "ARMED",
                "Armed trigger at " + sig.trigger() + " with SL " + sig.stop());

        // Arm the surveillance
        watcher.armTrigger(sig.symbol(), sig.trigger(),
                Duration.ofMillis(1200), Duration.ofMinutes(15), evt -> {
                    try {
                        // Check the spread for example before entering.
                        var q = alpaca.getLastQuote(evt.symbol());
                        BigDecimal ask = q.ask;
                        BigDecimal bid = q.bid;
                        int spreadBps = bid != null && ask != null && ask.signum() > 0
                                ? ask.subtract(bid).divide(ask, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(10000)).intValue()
                                : 0;

                        // If your barriers prevent entry
                        // if (spreadBps > MAX_SPREAD_BPS) { audit.record(evt.symbol(), "SKIPPED", "Spread too wide: " + spreadBps + "bps"); return; }

                        // Execute a purchase (executable limit)
                        BigDecimal limit = evt.trigger().multiply(BigDecimal.valueOf(1.002)); // مثال انزلاق 0.2%
                        var buyResp = alpaca.placeMarketableLimitBuy(evt.symbol(), qty, limit, extendedHours);
                        String buyOrderId = buyResp.path("id").asText("");

                        // Extract the average execution price
                        BigDecimal execPrice = alpaca.getOrderAvgFillPrice(buyOrderId);
                        if (execPrice == null) execPrice = evt.lastPrice();

                        // B - Execution log
                        audit.record(evt.symbol(), "ENTRY_FILLED",
                                "Bought " + qty + " @ " + execPrice, buyOrderId, buyResp.toString());

                        // Calculate TP and SL
                        BigDecimal tp = AlpacaClient.computeTP(execPrice, tpPercent);
                        BigDecimal sl = sig.stop();

                        // Put OCO
                        var ocoResp = alpaca.placeOCO(evt.symbol(), qty, tp, sl);

                        // C-OCO record
                        String parentId = ocoResp.path("id").asText("");
                        audit.record(evt.symbol(), "OCO_PLACED",
                                "TP=" + tp + ", SL=" + sl, parentId, ocoResp.toString());

                        // Lock Up monitoring is done at your usual location then use D
                    } catch (Exception e) {
                        audit.record(evt.symbol(), "ERROR", "Execution failed: " + e.getMessage());
                    }
                });
    }
}
