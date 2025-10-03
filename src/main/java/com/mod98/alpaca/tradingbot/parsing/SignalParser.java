package com.mod98.alpaca.tradingbot.parsing;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignalParser {

    // Example Message
    // ASTC
    // بتجاوز 6.36
    // وقف 5.78
    // اهداف
    // 6.86
    // 7.48
    // 8.16
    // 8.90

    private static final Pattern P =
            Pattern.compile("""
              ^\\s*([A-Za-z.\\-]+)\\s*\\R
              .*?(?:تجاوز|بتجاوز)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*\\R
              .*?وقف\\s+([0-9]+(?:\\.[0-9]+)?)\\s*
              (?:\\R.*?اهداف\\s+([0-9\\s\\.\\n]+))?
              """, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    public static Optional<TradeSignal> parse(String text) {
        Matcher m = P.matcher(text);
        if (!m.find()) return Optional.empty();

        String symbol = m.group(1).trim().toUpperCase(Locale.ROOT);
        BigDecimal trigger = new BigDecimal(m.group(2));
        BigDecimal stop = new BigDecimal(m.group(3));
        List<BigDecimal> targets = new ArrayList<>();

        if (m.group(4) != null) {
            for (String t : m.group(4).trim().split("\\s+")) {
                try {
                    targets.add(new BigDecimal(t));
                } catch (Exception ignored) {}
            }
        }

        return Optional.of(new TradeSignal(symbol, trigger, stop, targets));
    }
}
