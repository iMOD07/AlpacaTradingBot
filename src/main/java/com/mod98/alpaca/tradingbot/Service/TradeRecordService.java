package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Model.TradeRecord;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TradeRecordService {

    private static final Logger log = LoggerFactory.getLogger(TradeRecordService.class);

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void recordSignal(String symbol, BigDecimal trigger, BigDecimal sl) {
        TradeRecord rec = new TradeRecord();
        rec.setSymbol(symbol);
        rec.setTrigger(trigger);
        rec.setStopLoss(sl);
        rec.setState("ARMED");
        rec.setCreatedAt(Instant.now());
        em.persist(rec);
        log.info("📝 Signal recorded {} trigger={} SL={}", symbol, trigger, sl);
    }

    @Transactional
    public void recordEntry(String symbol, BigDecimal execPrice, int qty, String buyOrderId) {
        findOpen(symbol).ifPresentOrElse(rec -> {
            rec.setEntryPrice(execPrice);
            rec.setQty(qty);
            rec.setBuyOrderId(buyOrderId);
            rec.setState("FILLED");
            rec.setUpdatedAt(Instant.now());
            em.merge(rec);
            log.info("💰 Entry recorded {} @{} qty={}", symbol, execPrice, qty);
        }, () -> log.warn("recordEntry: no open trade for {}", symbol));
    }

    @Transactional
    public void recordExit(String symbol, BigDecimal exitPrice, String reason) {
        findOpen(symbol).ifPresentOrElse(rec -> {
            rec.setExitPrice(exitPrice);
            rec.setState(reason.toUpperCase());
            rec.setClosedAt(Instant.now());
            em.merge(rec);
            log.info("✅ Exit recorded {} @{} ({})", symbol, exitPrice, reason);
        }, () -> log.warn("recordExit: no open trade for {}", symbol));
    }

    private Optional<TradeRecord> findOpen(String symbol) {
        try {
            TradeRecord rec = em.createQuery(
                            "SELECT t FROM TradeRecord t WHERE t.symbol=:s AND t.closedAt IS NULL",
                            TradeRecord.class
                    ).setParameter("s", symbol)
                    .setMaxResults(1)
                    .getSingleResult();
            return Optional.of(rec);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
