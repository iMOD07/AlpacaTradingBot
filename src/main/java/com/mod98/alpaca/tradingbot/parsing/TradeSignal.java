package com.mod98.alpaca.tradingbot.parsing;

import java.math.BigDecimal;
import java.util.List;

public record TradeSignal(
        String symbol,
        BigDecimal trigger,
        BigDecimal stop,
        List<BigDecimal> targets
) {}
