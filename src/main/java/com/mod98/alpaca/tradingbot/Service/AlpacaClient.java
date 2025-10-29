package com.mod98.alpaca.tradingbot.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mod98.alpaca.tradingbot.Config.AlpacaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class AlpacaClient {

    private final String keyId;
    private final String secretKey;
    private final String baseUrl;
    private final String dataUrl;

    private static final int MAX_RETRIES = 2;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public AlpacaClient(AlpacaProperties props) {
        this.keyId = props.getApiKeyId();
        this.secretKey = props.getApiSecretKey();
        this.baseUrl = props.getBaseUrl();
        this.dataUrl = props.getDataUrl();

        if (keyId == null || secretKey == null || baseUrl == null || dataUrl == null) {
            throw new IllegalStateException("AlpacaProperties is not fully configured");
        }
        try {
            JsonNode acc = getAccount();
            System.out.println("Alpaca API connected successfully. Account ID: " + acc.path("id").asText("unknown"));
        } catch (Exception e) {
            System.err.println("Failed to connect to Alpaca API → " + e.getMessage());
        }
    }

    // ---- Public APIs (Account information) ----
    public JsonNode getAccount() throws IOException, InterruptedException {
        String url = baseUrl + "/v2/account";
        HttpResponse<String> r = sendWithRetry(req("GET", url, null).build(), MAX_RETRIES);
        ensure2xx(r);
        return mapper.readTree(r.body());
    }

    // Last trading price
    public BigDecimal getLastTradePrice(String symbol) throws IOException, InterruptedException {
        String url = dataUrl + "/stocks/" + symbol + "/trades/latest";
        HttpResponse<String> r = sendWithRetry(req("GET", url, null).build(), MAX_RETRIES);
        ensure2xx(r);
        JsonNode p = mapper.readTree(r.body()).path("trade").path("p");
        if (!p.isNumber()) {
            throw new IllegalStateException("No latest trade price for: " + symbol);
        }
        return p.decimalValue();
    }

    // Latest bid/ask
    public static final class Quote {
        public final BigDecimal bid;
        public final BigDecimal ask;
        public Quote(BigDecimal bid, BigDecimal ask) { this.bid = bid; this.ask = ask; }
    }

    public Quote getLastQuote(String symbol) throws IOException, InterruptedException {
        String url = dataUrl + "/stocks/" + symbol + "/quotes/latest";
        HttpResponse<String> r = sendWithRetry(req("GET", url, null).build(), MAX_RETRIES);
        ensure2xx(r);
        JsonNode root = mapper.readTree(r.body()).path("quote");
        BigDecimal bid = root.path("bp").isNumber() ? root.path("bp").decimalValue() : null;
        BigDecimal ask = root.path("ap").isNumber() ? root.path("ap").decimalValue() : null;
        if (bid == null || ask == null)
            throw new IllegalStateException("No quote for: " + symbol);
        return new Quote(bid, ask);
    }

    // Price profiling (fractional accuracy)
    private BigDecimal normalizePrice(BigDecimal px) {
        if (px == null) return null;
        return px.compareTo(BigDecimal.ONE) >= 0
                ? px.setScale(2, RoundingMode.HALF_UP)
                : px.setScale(4, RoundingMode.HALF_UP);
    }

    // Buy entry
    public JsonNode placeMarketableLimitBuy(String symbol, int qty, BigDecimal limitPrice, boolean extendedHours)
            throws IOException, InterruptedException {
        String url = baseUrl + "/v2/orders";
        BigDecimal lp = normalizePrice(limitPrice);
        String body = mapper.writeValueAsString(Map.of(
                "symbol", symbol,
                "qty", String.valueOf(qty),
                "side", "buy",
                "type", "limit",
                "time_in_force", "day",
                "limit_price", lp.toPlainString(),
                "extended_hours", extendedHours
        ));
        HttpResponse<String> r = sendWithRetry(req("POST", url, body).build(), MAX_RETRIES);
        ensure2xx(r);
        return mapper.readTree(r.body());
    }

    // Exit (profit or loss)
    public JsonNode placeOCO(String symbol, int qty, BigDecimal takeProfitLimitPrice, BigDecimal stopLossStopPrice)
            throws IOException, InterruptedException {
        String url = baseUrl + "/v2/orders";
        BigDecimal tp = normalizePrice(takeProfitLimitPrice);
        BigDecimal sl = normalizePrice(stopLossStopPrice);
        String body = mapper.writeValueAsString(Map.of(
                "symbol", symbol,
                "qty", String.valueOf(qty),
                "side", "sell",
                "type", "limit",
                "time_in_force", "gtc",
                "order_class", "oco",
                "take_profit", Map.of("limit_price", tp.toPlainString()),
                "stop_loss", Map.of("stop_price", sl.toPlainString())
        ));
        HttpResponse<String> r = sendWithRetry(req("POST", url, body).build(), MAX_RETRIES);
        ensure2xx(r);
        return mapper.readTree(r.body());
    }

    // Cancel your Order
    public void cancelOrder(String orderId) throws IOException, InterruptedException {
        String url = baseUrl + "/v2/orders/" + orderId;
        HttpResponse<String> r = sendWithRetry(req("DELETE", url, null).build(), MAX_RETRIES);
        ensure2xx(r);
    }

    // Average execution price
    public BigDecimal getOrderAvgFillPrice(String orderId) throws IOException, InterruptedException {
        String url = baseUrl + "/v2/orders/" + orderId;
        HttpResponse<String> r = sendWithRetry(req("GET", url, null).build(), MAX_RETRIES);
        ensure2xx(r);
        JsonNode root = mapper.readTree(r.body());
        String status = root.path("status").asText("");
        if ("filled".equalsIgnoreCase(status) || "partially_filled".equalsIgnoreCase(status)) {
            JsonNode p = root.path("filled_avg_price");
            if (!p.isMissingNode() && !p.isNull() && !p.asText().isBlank()) {
                return new BigDecimal(p.asText());
            }
        }
        return null;
    }

    // Fetch orders
    public JsonNode listOrders(String status, String side, Instant since, int limit)
            throws IOException, InterruptedException {
        String base = baseUrl + "/v2/orders?status=" + encode(status)
                + "&side=" + encode(side)
                + "&limit=" + limit
                + "&nested=true";
        if (since != null) {
            String after = DateTimeFormatter.ISO_INSTANT.format(since);
            base += "&after=" + encode(after);
        }
        HttpResponse<String> r = sendWithRetry(req("GET", base, null).build(), MAX_RETRIES);
        ensure2xx(r);
        return mapper.readTree(r.body());
    }

    // ---- Helpers ----
    private HttpRequest.Builder req(String method, String url, String jsonBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        // Idempotency-Key for everything except GET
        if (!"GET".equalsIgnoreCase(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }

        if ("GET".equalsIgnoreCase(method)) return b.GET();
        if ("DELETE".equalsIgnoreCase(method)) return b.DELETE();
        return b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
    }

    // Send with retry for temporary network/server conditions
    private HttpResponse<String> sendWithRetry(HttpRequest req, int maxRetries)
            throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                // Retry on 429 (Rate limit) or 5xx (Server) codes
                if (code == 429 || code >= 500) {
                    if (attempt >= maxRetries) return resp; // let ensure2xx handle it
                    Thread.sleep(2000L * (attempt + 1));
                    attempt++;
                    continue;
                }
                return resp;
            } catch (IOException e) {
                if (attempt >= maxRetries) throw e;
                Thread.sleep(2000L * (attempt + 1));
                attempt++;
            }
        }
    }

    private static void ensure2xx(HttpResponse<String> r) {
        int s = r.statusCode();
        if (s < 200 || s >= 300) throw new IllegalStateException("Alpaca HTTP " + s + " → " + r.body());
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // We keep high precision here, then pass through normalizePrice when sending.
    public static BigDecimal computeTP(BigDecimal executionPrice, BigDecimal pct) {
        return executionPrice.multiply(
                BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
        );
    }
}
