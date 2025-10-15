package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Model.TradeEvent;
import com.mod98.alpaca.tradingbot.Repository.TradeEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class TradeAuditService {

    private static final Logger log = LoggerFactory.getLogger(TradeAuditService.class);
    private final TradeEventRepository repo;

    public TradeEvent record(String symbol, String eventType, String message) {
        return record(symbol, eventType, message, null, null);
    }
    public TradeEvent record(String symbol, String eventType, String message, String orderId) {
        return record(symbol, eventType, message, orderId, null);
    }
    public TradeEvent record(String symbol, String eventType, String message, String orderId, String payloadJson) {
        try {
            TradeEvent ev = new TradeEvent();
            ev.setSymbol(symbol);
            ev.setEventType(eventType);
            ev.setMessage(message);
            ev.setOrderId(orderId);
            ev.setPayload(payloadJson);
            TradeEvent saved = repo.save(ev);
            log.info("[AUDIT:{}] symbol={} orderId={} msg={}", eventType, symbol, orderId, message);
            return saved;
        } catch (Exception e) {
            log.error("[AUDIT:ERROR] Failed to persist audit event: {}", e.getMessage(), e);
            return null;
        }
    }

    public void info(String msg, Object... args)  { log.info("[AUDIT] " + msg, args); }
    public void warn(String msg, Object... args)  { log.warn("[AUDIT] " + msg, args); }
    public void error(String msg, Object... args) { log.error("[AUDIT] " + msg, args); }
}
