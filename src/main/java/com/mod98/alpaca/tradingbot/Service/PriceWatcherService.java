package com.mod98.alpaca.tradingbot.Service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Service
public class PriceWatcherService {

    private static final Logger log = LoggerFactory.getLogger(PriceWatcherService.class);

    private final AlpacaClient alpaca;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentMap<String, WatchHandle> active = new ConcurrentHashMap<>();

    private final Duration defaultPollInterval;
    private final Duration defaultTimeout;

    @Autowired
    public PriceWatcherService(AlpacaClient alpaca) {
        this(alpaca, Duration.ofSeconds(1), Duration.ofMinutes(10));
    }

    public PriceWatcherService(AlpacaClient alpaca, Duration pollInterval, Duration timeout) {
        this.alpaca = Objects.requireNonNull(alpaca, "alpaca");
        this.defaultPollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        this.defaultTimeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        this.scheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> { Thread t = new Thread(r, "PriceWatcher"); t.setDaemon(true); return t; }
        );
    }

    public record TriggerEvent(String symbol, BigDecimal trigger, BigDecimal lastPrice, Instant crossedAt) {}

    public interface Arm {
        void cancel();
        boolean isActive();
        String id();
    }

    public Arm armTrigger(String symbol,
                          BigDecimal trigger,
                          Duration pollEvery,
                          Duration timeout,
                          Consumer<TriggerEvent> onCross) {

        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(onCross, "onCross");

        final String sym = symbol.trim().toUpperCase();
        final BigDecimal trg = trigger.setScale(6, RoundingMode.HALF_UP);
        final String key = sym + "|" + trg.toPlainString();

        if (active.containsKey(key)) {
            log.warn("Watch already active for {}", key);
            return active.get(key);
        }

        final Duration poll = Optional.ofNullable(pollEvery).orElse(defaultPollInterval);
        final Duration to = Optional.ofNullable(timeout).orElse(defaultTimeout);
        final Instant expiresAt = Instant.now().plus(to);
        final String id = UUID.randomUUID().toString();

        // Periodic inspection mission
        Runnable task = () -> {
            try {
                if (Instant.now().isAfter(expiresAt)) {
                    log.info("⏳ Timeout watching {}@{}", sym, trg);
                    cancelInternal(key, /*interrupt*/ false);
                    return;
                }

                BigDecimal last = alpaca.getLastTradePrice(sym);
                if (last != null && last.compareTo(trg) >= 0) {
                    // Drag the handle and remove it from the map first to prevent duplication
                    WatchHandle handle = active.remove(key);
                    if (handle == null) return;
                    // Stop scheduling without interrupting the current thread
                    try { handle.future.cancel(false); } catch (Exception ignored) {}
                    TriggerEvent evt = new TriggerEvent(sym, trg, last, Instant.now());
                    // Execute the callback on a separate thread of the same scheduler.
                    scheduler.execute(() -> {
                        try {
                            handle.callback.accept(evt);
                        } catch (Throwable t) {
                            log.error("onCross callback error", t);
                        }
                    });
                }
            } catch (Throwable t) {
                log.error("Polling error for {}: {}", sym, t.getMessage(), t);
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                task, 0L, Math.max(100, poll.toMillis()), TimeUnit.MILLISECONDS
        );

        WatchHandle handle = new WatchHandle(id, key, future, onCross);
        active.put(key, handle);

        // Auto-cancel after timeout (extra protection) — no interruption
        scheduler.schedule(() -> {
            if (active.containsKey(key)) {
                log.info("⏳ Auto-cancel (timeout) for {}", key);
                cancelInternal(key, /*interrupt*/ false);
            }
        }, to.toMillis(), TimeUnit.MILLISECONDS);

        log.info("👀 Armed trigger: {} @ {} (poll={}, timeout={}, id={})", sym, trg, poll, to, id);
        return handle;
    }

    private void cancelInternal(String key, boolean interrupt) {
        WatchHandle h = active.remove(key);
        if (h != null) {
            try { h.future.cancel(interrupt); } catch (Exception ignored) {}
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Stopping PriceWatcherService...");
        active.keySet().forEach(k -> cancelInternal(k, false));
        scheduler.shutdownNow();
    }

    private static class WatchHandle implements Arm {
        final String id;
        final String key;
        final ScheduledFuture<?> future;
        final Consumer<TriggerEvent> callback;
        WatchHandle(String id, String key, ScheduledFuture<?> future, Consumer<TriggerEvent> callback) {
            this.id = id; this.key = key; this.future = future; this.callback = callback;
        }
        @Override public void cancel() { future.cancel(false); }
        @Override public boolean isActive() { return !future.isDone() && !future.isCancelled(); }
        @Override public String id() { return id; }
    }
}
