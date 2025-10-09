package com.mod98.alpaca.tradingbot.Config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "trade")
@Validated
@Getter
@Setter
public class TradeProperties {

    @NotNull
    private BigDecimal fixedBudget;

    @NotNull
    private BigDecimal tpPercent;

}
