package com.mod98.alpaca.tradingbot.telegram;

import com.mod98.alpaca.tradingbot.config.TelegramProperties;
import com.mod98.alpaca.tradingbot.config.TradeProperties;
import com.mod98.alpaca.tradingbot.logic.TradeLogic;
import com.mod98.alpaca.tradingbot.parsing.AiSignalParser;
import com.mod98.alpaca.tradingbot.parsing.SignalParser;
import com.mod98.alpaca.tradingbot.parsing.TradeSignal;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;

@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    private final TelegramProperties props;
    private final TradeProperties tradeProps;

    // ========== from application.properties ==========
    @Value("${parser.regex.enabled}")
    private boolean regexEnabled;

    @Value("${parser.ai.enabled}")
    private boolean aiEnabled;

    @Value("${trade.fixed-budget}")
    private BigDecimal budgetUSD;

    @Value("${trade.tp-percent}")
    private BigDecimal takeProfitPct;
    // ==================================================
    private TradeLogic logic;
    private AiSignalParser aiParser;
    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    public TelegramClientService(TelegramProperties props, TradeProperties tradeProps) {
        this.props = props;
        this.tradeProps = tradeProps;
    }

    @PostConstruct
    public void initAndStart() {
        Long targetId = (props.getChannelTelegram() != null) ? props.getChannelTelegram().getId() : null;
        log.info("Target channel id from properties: {}", targetId);
        if (targetId == null) {
            throw new IllegalStateException("telegram.channel.id is missing!");
        }

        // this.logic = new TradeLogic(tradeProps.getFixedBudget(), tradeProps.getTpPercent());
        // log.info("Trade config  → Budget=${}, TP%={}", tradeProps.getFixedBudget(), tradeProps.getTpPercent());

        // AI Parser
        if (aiEnabled) {
            String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY",
                    System.getProperty("openai.api.key", ""));
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("AI enabled but missing OPENAI_API_KEY, then disabling AI parser.");
                aiEnabled = false;
            } else {
                this.aiParser = new AiSignalParser(apiKey);
                log.info("🤖 AI Parser enabled 🤖.");
            }
        }
        log.info("Parser config ,, Regex={}, AI={}", regexEnabled, aiEnabled);
        log.info("Trade config ,, Budget=${}, TP%={}", budgetUSD, takeProfitPct);

        // ===== TDLight start =====
        try {
            APIToken apiToken = new APIToken(props.getApiId(), props.getApiHash());
            TDLibSettings settings = TDLibSettings.create(apiToken);
            Path base = Paths.get(props.getSessionDir());
            settings.setDatabaseDirectoryPath(base.resolve("db"));
            settings.setDownloadedFilesDirectoryPath(base.resolve("files"));

            clientFactory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = clientFactory.builder(settings);

            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);

            AuthenticationSupplier<?> auth = AuthenticationSupplier.user(props.getPhone());
            client = builder.build(auth);

            Long targetChatId = (props.getChannelTelegram() != null) ? props.getChannelTelegram().getId() : null;
            log.info("TDLight client is ready. Watching chatId={}", targetChatId);
        } catch (Throwable t) {
            log.error("Failed to start Telegram client", t);
            throw new RuntimeException(t);
        }
    }

    private void onAuthUpdate(TdApi.UpdateAuthorizationState upd) {
        var st = upd.authorizationState;

        if (st instanceof TdApi.AuthorizationStateWaitCode) {
            log.warn("Authorization: WAIT CODE — enter the code:");
            System.out.print("Enter Telegram login code: ");
            String code = new Scanner(System.in).nextLine().trim();
            client.send(new TdApi.CheckAuthenticationCode(code));

        } else if (st instanceof TdApi.AuthorizationStateWaitPassword) {
            log.warn("Authorization: WAIT 2FA PASSWORD — enter your password:");
            System.out.print("Enter 2FA password: ");
            String password = new Scanner(System.in).nextLine();
            client.send(new TdApi.CheckAuthenticationPassword(password));

        } else if (st instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation conf) {
            log.info("Authorization: WAIT QR CONFIRMATION → {}", conf.link);

        } else if (st instanceof TdApi.AuthorizationStateReady) {
            log.info("Authorization: READY ✅");

        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            log.info("Authorization: CLOSED");
        }
    }

    private void onNewMessage(TdApi.UpdateNewMessage update) {
        TdApi.Message msg = update.message;
        long chatId = msg.chatId;

        // Channel filtering: We use the ID as it is from properties
        Long wanted = (props.getChannelTelegram() != null) ? props.getChannelTelegram().getId() : null;
        if (wanted == null || wanted == 0) {
            // ignore everything
            if (log.isDebugEnabled()) {
                log.debug("No telegram.channel.id configured — skipping all messages. chatId={}", chatId);
            }
            return;
        }

        if (!Objects.equals(chatId, wanted)) {
            return; // do not print Anything from Channels other
        }

        if (!(msg.content instanceof TdApi.MessageText text)) {
            return;
        }
        String body = text.text.text;
        if (body == null || body.isBlank()) {
            return;
        }

        log.info("Incoming message [{}]:\n{}", chatId, body);

        // 1 Regex Activated
        if (regexEnabled) {
            Optional<TradeSignal> parsed = SignalParser.parse(body);
            if (parsed.isPresent()) {
                TradeSignal s = parsed.get();
                var plan = logic.buildPlan(s);
                log.info("✅ Parsed via REGEX: symbol={}, trigger={}, SL={}, targets={}",
                        s.symbol(), s.trigger(), s.stop(), s.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), takeProfitPct, plan.sl());
                return;
            } else {
                log.warn("⚠️ Regex parser failed for this message.");
            }
        }

        // 2 AI Activated
        if (aiEnabled && aiParser != null) {
            Optional<TradeSignal> aiParsed = aiParser.parse(body);
            if (aiParsed.isPresent()) {
                TradeSignal s = aiParsed.get();
                var plan = logic.buildPlan(s);
                log.info("🤖 Parsed via AI: symbol={}, trigger={}, SL={}, targets={}",
                        s.symbol(), s.trigger(), s.stop(), s.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), takeProfitPct, plan.sl());
            } else {
                log.error("❌ AI parser failed as well — skipping this message.");
            }
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (client != null) {
                client.close();
                log.info("TDLight client stopped.");
            }
        } catch (Exception ignored) {}
        try {
            if (clientFactory != null) {
                clientFactory.close();
                log.info("TDLight factory closed.");
            }
        } catch (Exception ignored) {}
    }
}