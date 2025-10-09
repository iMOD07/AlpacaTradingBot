package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Parsing.TradeSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TradeLogic {

    private final BigDecimal budgetUSD;
    private final BigDecimal takeProfitPct;

    public TradeLogic(BigDecimal budgetUSD, BigDecimal takeProfitPct) {
        this.budgetUSD = budgetUSD;
        this.takeProfitPct = takeProfitPct;
    }

    public record Plan(int qty, BigDecimal tp, BigDecimal sl) {}

    public Plan buildPlan(TradeSignal s) {
        int qty = budgetUSD
                .divide(s.trigger(), 10, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.CEILING)
                .intValue();

        BigDecimal tp = s.trigger()
                .multiply(BigDecimal.ONE.add(
                        takeProfitPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal sl = s.stop();

        return new Plan(qty, tp, sl);
    }
}
