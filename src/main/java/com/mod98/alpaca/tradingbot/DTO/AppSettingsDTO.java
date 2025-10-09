package com.mod98.alpaca.tradingbot.DTO;

import java.math.BigDecimal;

public record AppSettingsDTO(
        boolean regexEnabled,
        boolean aiEnabled,
        BigDecimal fixedBudget,
        BigDecimal tpPercent,
        Long channelId,
        String sessionDir
) {}
