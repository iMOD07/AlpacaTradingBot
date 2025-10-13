package com.mod98.alpaca.tradingbot.Config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "alpaca")
@Validated
@Getter
@Setter
public class AlpacaProperties {

    @NotBlank
    private String baseUrl; // https://paper-api.alpaca.markets

    @NotBlank
    private String dataUrl; // https://data.alpaca.markets

    @NotBlank
    private String apiKeyId;

    @NotBlank
    private String apiSecretKey;

    private boolean extendedHours = true;

    @NotNull
    private Integer maxSlippageBps = 30; // 0.30%

    @NotNull
    private Integer spreadGuardBps = 50; // 0.50%

    @NotNull
    private Integer orderTimeoutSec = 15;

    @NotNull
    private Integer pollIntervalMs = 1200;

}
