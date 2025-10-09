package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Repository.AppSettingsRepository;
import com.mod98.alpaca.tradingbot.Model.AppSettings;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class SettingsService {

    @Autowired
    private final AppSettingsRepository repo;

    // Simple cash
    private AppSettings cached;
    private Instant lastLoad = Instant.EPOCH;
    private final Duration ttl = Duration.ofSeconds(10);

    public SettingsService(AppSettingsRepository repo) {
        this.repo = repo;
    }

    public synchronized AppSettings get() {
        if (cached == null || Instant.now().isAfter(lastLoad.plus(ttl))) {
            cached = repo.findById(1L).orElseThrow(() -> new NoSuchElementException("app_settings not initialized"));
            lastLoad = Instant.now();
        }
        return cached;
    }

    @Transactional
    public synchronized AppSettings update(AppSettings incoming) {
        incoming.setId(1L);
        incoming.setUpdatedAt(Instant.now());
        AppSettings saved = repo.save(incoming);
        cached = saved;
        lastLoad = Instant.now();
        return saved;
    }
}