package com.mod98.alpaca.tradingbot.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.math.BigDecimal;
import java.util.*;

// OpenAI is used to analyze text trading recommendations
// returns a TradeSignal object containing the symbol + entry + stop + targets.
public class AiSignalParser {

    private final OpenAIClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiSignalParser(String apiKey) {
        // Create an OpenAI client (either with an environmental key or directly)
        if (apiKey == null || apiKey.isBlank()) {
            this.client = OpenAIOkHttpClient.fromEnv(); // If it is present in the ENV
        } else {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
        }
    }

    public Optional<TradeSignal> parse(String message) {
        try {
            String prompt = """
                أنت محلل تداول ذكي.
                حلل نص توصية الأسهم التالية، واستخرج:
                - الرمز (symbol)
                - سعر الدخول (trigger)
                - وقف الخسارة (stop)
                - الأهداف (targets) إن وُجدت
                أعد النتيجة كـ JSON فقط بالشكل التالي بدون أي تعليق إضافي:
                {"symbol":"FGNX","trigger":9.16,"stop":8.25,"targets":[10.00,11.16,12.57]}
                النص:
                """ + message;

            // Create Order to GPT
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .addSystemMessage("أنت محلل توصيات أسهم محترف.")
                    .addUserMessage(prompt)
                    .model(ChatModel.GPT_5) // أو GPT_3_5_TURBO لو عندك نسخة أقدم
                    .temperature(0.1)
                    .maxCompletionTokens(200L)
                    .build();

            ChatCompletion result = client.chat().completions().create(params);

            String content = result.choices()
                    .get(0)
                    .message()
                    .content()
                    .orElse("")
                    .trim();

            // Clean up output (remove ```json if present)
            content = content.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            // Parse the resulting JSON
            Map<String, Object> json = mapper.readValue(
                    content,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );

            String symbol = (String) json.get("symbol");
            BigDecimal trigger = toBigDecimal(json.get("trigger"));
            BigDecimal stop = toBigDecimal(json.get("stop"));

            List<BigDecimal> targets = new ArrayList<>();
            if (json.get("targets") instanceof List<?> list) {
                for (Object t : list) {
                    BigDecimal v = toBigDecimal(t);
                    if (v != null) targets.add(v);
                }
            }

            if (symbol == null || trigger == null || stop == null)
                return Optional.empty();

            return Optional.of(new TradeSignal(symbol, trigger, stop, targets));

        } catch (Exception e) {
            System.err.println("❌ AI Parser error: " + e.getMessage());
            return Optional.empty();
        }
    }

    private BigDecimal toBigDecimal(Object o) {
        try {
            return o == null ? null : new BigDecimal(o.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
