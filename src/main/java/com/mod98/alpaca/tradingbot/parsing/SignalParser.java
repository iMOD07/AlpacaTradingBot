package com.mod98.alpaca.tradingbot.parsing;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignalParser {

    // Numbers supporting Latin + Arabic-Indian with separators (., ٬ U+066B)
    private static final String NUM = "([0-9\\u0660-\\u0669]+(?:[\\.,\\u066B\\u066C][0-9\\u0660-\\u0669]+)?)";

    // The first line (or the first non-empty line) usually contains the English symbol.
    private static final Pattern SYMBOL_LINE = Pattern.compile("([A-Za-z][A-Za-z0-9.\\-]{1,10})");

    // After defining NUM
    private static final String SEP = "[\\s:\\u061B=\\-–—\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]*";

    // We separate the expressions into several patterns and test them in sequence.
    // ===== Entry patterns (Trigger) =====
    private static final Pattern P_B_TAJAWAZ =
            Pattern.compile("(?i)(?:^|\\s)بت?جاوز(?:ان)?" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_YATAJAWAZ =
            Pattern.compile("(?i)(?:^|\\s)يتجاوز" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_AT_TAJAWAZ =
            Pattern.compile("(?i)(?:^|\\s)عند\\s*(?:تجاو?ز|اختراق)" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_IKHTIRAQ =
            Pattern.compile("(?i)(?:^|\\s)اختراق" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_DUKHOOL_PREP =
            Pattern.compile("(?i)(?:^|\\s)دخول\\s*(?:عند|فوق|على|بعد)" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_DUKHOOL_SIMPLE =
            Pattern.compile("(?i)(?:^|\\s)دخول" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_SHIRAA_AT =
            Pattern.compile("(?i)(?:^|\\s)شراء\\s*عند" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_IGLAQ_MIN =
            Pattern.compile("(?i)(?:^|\\s)(?:اغلاق|إغلاق)\\s*(?:دقيقة)?" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_IGLAQ_FAWQ =
            Pattern.compile("(?i)(?:^|\\s)(?:اغلاق|إغلاق)\\s*فوق" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_THABAT_FAWQ =
            Pattern.compile("(?i)(?:^|\\s)ثبات\\s*فوق" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern P_ENTRY_EN =
            Pattern.compile("(?i)(?:^|\\s)(?:Entry|Buy)" + SEP + NUM + "(?:\\s|$)", Pattern.DOTALL | Pattern.UNICODE_CASE);

    private static final List<Pattern> TRIGGER_PATTERNS = List.of(
            P_B_TAJAWAZ,
            P_YATAJAWAZ,
            P_AT_TAJAWAZ,
            P_IKHTIRAQ,
            P_DUKHOOL_PREP,
            P_DUKHOOL_SIMPLE,
            P_SHIRAA_AT,
            P_IGLAQ_MIN,
            P_IGLAQ_FAWQ,
            P_THABAT_FAWQ,
            P_ENTRY_EN
    );
    // Stop: Support multiple formats
    private static final Pattern STOP_P = Pattern.compile(
            "(?i)(?:^|\\s)(?:وقف(?:\\s*خسارة)?|ستوب|ايقاف(?:\\s*خسارة)?|Stop|SL)\\s*" + NUM + "(?:\\s|$)",
            Pattern.DOTALL | Pattern.UNICODE_CASE);

    //Any number (same as NUM) — for targets
    private static final Pattern ANY_NUMBER = Pattern.compile(NUM, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Goal Headers: Arabic/English — Without \b
    private static final Pattern TARGETS_HEAD = Pattern.compile(
            "(?i)(?:^|\\s)(?:اهداف|الاهداف|الأهداف|هدف|Targets?|TP)(?:\\s|$)",
            Pattern.DOTALL | Pattern.UNICODE_CASE);

    public static Optional<TradeSignal> parse(String raw) {
        if (raw == null) return Optional.empty();

        // 1 - Normalize numbers + unify commas
        String text = normalizeDigits(raw)
                .replace('٬', ',')  // U+066C thousands
                .replace('٫', '.'); // U+066B decimal

        // 2 - Symbol: The first non-blank line containing a Latin symbol.
        String symbol = null;
        for (String line : text.split("\\R")) {
            String ln = line.trim();
            if (ln.isEmpty()) continue;
            Matcher sm = SYMBOL_LINE.matcher(ln);
            if (sm.find()) {
                symbol = sm.group(1).toUpperCase(Locale.ROOT);
                break;
            }
        }
        if (symbol == null) return Optional.empty();

        // 3 - Trigger: The first match of patterns in sequence.
        BigDecimal trigger = null;
        for (Pattern p : TRIGGER_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                trigger = toBigDecimal(m.group(1));
                break;
            }
        }

        // 4 - Stop
        BigDecimal stop = null;
        Matcher sm = STOP_P.matcher(text);
        if (sm.find()) {
            stop = toBigDecimal(sm.group(1));
        }

        // 5 - Targets: All numbers after the targets header
        List<BigDecimal> targets = new ArrayList<>();
        Matcher th = TARGETS_HEAD.matcher(text);
        if (th.find()) {
            int start = th.end();
            String afterTargets = text.substring(start);
            Matcher nums = ANY_NUMBER.matcher(afterTargets);
            while (nums.find()) {
                targets.add(toBigDecimal(nums.group(1)));
            }
        }

        if (trigger == null || stop == null) return Optional.empty();
        return Optional.of(new TradeSignal(symbol, trigger, stop, targets));
    }

    // ===== Helpers =====
    //** Convert Arabic/Persian numbers to Latin */

    private static String normalizeDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // Arabic-Indic 0-9: \u0660 - \u0669
            if (ch >= '\u0660' && ch <= '\u0669') {
                ch = (char) ('0' + (ch - '\u0660'));
            }
            // Extended Arabic-Indic (Persian) 0-9: \u06F0 - \u06F9
            else if (ch >= '\u06F0' && ch <= '\u06F9') {
                ch = (char) ('0' + (ch - '\u06F0'));
            }
            out.append(ch);
        }
        return out.toString();
    }

    // Unify decimal separator then BigDecimal
    private static BigDecimal toBigDecimal(String num) {
        return new BigDecimal(
                num.replace('\u066B', '.')  // ARABIC DECIMAL
                        .replace('\u066C', ',')  // ARABIC THOUSANDS (convert it to a regular comma)
                        .replace(',', '.')       // All commas turn into '.'
        );
    }
}
