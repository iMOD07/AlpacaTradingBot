
package com.mod98.alpaca.tradingbot.Config;

import com.mod98.alpaca.tradingbot.Model.AppSettings;
import com.mod98.alpaca.tradingbot.Repository.AppSettingsRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;

@Configuration
public class BootstrapConfig {

    @Bean
    CommandLineRunner seedAppSettings(AppSettingsRepository repo) {
        return args -> {
            if (repo.findById(1L).isEmpty()) {
                AppSettings s = new AppSettings();
                s.setId(1L);
                s.setRegexEnabled(true);
                s.setAiEnabled(false);
                s.setFixedBudget(new BigDecimal("200.00"));
                s.setTpPercent(new BigDecimal("5.00"));
                s.setSessionDir("./tdlight-session");
                s.setAlpacaExtendedHours(true);
                s.setUpdatedAt(Instant.now());
                // ضع channelId الافتراضي من application.properties إذا تبي:
                // s.setChannelId(-1002915197770L);
                repo.save(s);
            }
        };
    }
}
