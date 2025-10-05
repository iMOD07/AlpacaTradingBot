package com.mod98.alpaca.tradingbot.telegram;

import com.mod98.alpaca.tradingbot.config.TelegramProperties;
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
    private final TradeLogic logic;
    private final boolean regexEnabled;
    private final boolean aiEnabled;
    private AiSignalParser aiParser;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    public TelegramClientService(TelegramProperties props) {
        this.props = props;

        // Trading preferences
        this.logic = new TradeLogic(
                new BigDecimal(System.getProperty("trade.fixed-budget", "200")),
                new BigDecimal(System.getProperty("trade.tp.percent", "5"))
        );

        // Read toggles from application.properties or environment
        this.regexEnabled = Boolean.parseBoolean(System.getProperty(
                "parser.regex.enabled",
                System.getenv().getOrDefault("PARSER_REGEX_ENABLED", "true")
        ));

        this.aiEnabled = Boolean.parseBoolean(System.getProperty(
                "parser.ai.enabled",
                System.getenv().getOrDefault("PARSER_AI_ENABLED", "false")
        ));

        if (aiEnabled) {
            String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY",
                    System.getProperty("openai.api.key", ""));
            if (apiKey.isBlank()) {
                log.warn("AI enabled but missing OPENAI_API_KEY → disabling fallback.");
            } else {
                this.aiParser = new AiSignalParser(apiKey);
                log.info("🤖 AI Parser enabled.");
            }
        }

        log.info("Parser config → Regex={}, AI={}", regexEnabled, aiEnabled);
    }

    @PostConstruct
    public void start() {
        try {
            // 1 - Token setup + Settings + Session paths
            APIToken apiToken = new APIToken(props.getApiId(), props.getApiHash());
            TDLibSettings settings = TDLibSettings.create(apiToken);
            Path base = Paths.get(props.getSessionDir());
            settings.setDatabaseDirectoryPath(base.resolve("db"));
            settings.setDownloadedFilesDirectoryPath(base.resolve("files"));

            // 2 - Factory + Builder
            clientFactory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = clientFactory.builder(settings);

            // 3 - Handlers
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);

            // 4 Create a client by logging in as a user (with mobile number)
            AuthenticationSupplier<?> auth = AuthenticationSupplier.user(props.getPhone());
            client = builder.build(auth);

            log.info("TDLight client is ready. Watching chatId={}",
                    (props.getChannel() != null ? props.getChannel().getId() : null));

        } catch (Throwable t) {
            log.error("Failed to start Telegram client", t);
            throw new RuntimeException(t);
        }
    }

    private void onAuthUpdate(TdApi.UpdateAuthorizationState upd) {
        var st = upd.authorizationState;

        if (st instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            log.info("Authorization: WAIT TDLIB PARAMS");
        } else if (st instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            log.info("Authorization: WAIT PHONE (will be provided by AuthenticationSupplier)");

            // Usually AuthenticationSupplier.user(phone) is enough
            // If you need to manually:
            // client.send(new TdApi.SetAuthenticationPhoneNumber(props.getPhone(), null));
        } else if (st instanceof TdApi.AuthorizationStateWaitCode) {
            log.warn("Authorization: WAIT CODE _ Enter the code you received from Telegram now");
            System.out.print("Enter Telegram login code: ");
            String code = new Scanner(System.in).nextLine().trim();
            client.send(new TdApi.CheckAuthenticationCode(code));
        } else if (st instanceof TdApi.AuthorizationStateWaitPassword) {
            log.warn("Authorization: WAIT 2FA PASSWORD _ Enter your two step verification password");
            System.out.print("Enter 2FA password: ");
            String password = new Scanner(System.in).nextLine();
            client.send(new TdApi.CheckAuthenticationPassword(password));
        } else if (st instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation conf) {
            log.info("Authorization: WAIT QR CONFIRMATION _ Open the link from another device: {}", conf.link);
        } else if (st instanceof TdApi.AuthorizationStateReady) {
            log.info("Authorization: READY ✅");
        } else if (st instanceof TdApi.AuthorizationStateClosing) {
            log.info("Authorization: CLOSING");
        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            log.info("Authorization: CLOSED");
        }
    }

    private void onNewMessage(TdApi.UpdateNewMessage update) {
        TdApi.Message msg = update.message;
        long chatId = msg.chatId;

        // Filter by the desired channel
        Long wanted = (props.getChannel() != null) ? props.getChannel().getId() : null;
        if (wanted != null && wanted != 0 && !Objects.equals(chatId, wanted)) {
            return;
        }

        if (msg.content instanceof TdApi.MessageText text) {
            String body = text.text.text;
            log.info("Incoming message [{}]:\n{}", chatId, body);



            // 1 First try via Regex

            Optional<TradeSignal> parsed = SignalParser.parse(body);
            if (parsed.isPresent()) {
                TradeSignal s = parsed.get();
                var plan = logic.buildPlan(s);
                log.info("✅ Parsed via REGEX: symbol={}, trigger={}, SL={}, targets={}",
                        s.symbol(), s.trigger(), s.stop(), s.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(),
                        System.getProperty("trade.tp.percent", "5"),
                        plan.sl());
                return;
            }

            // 2 - If regex fails and AI is enabled try AI
            if (aiEnabled && aiParser != null) {
                log.warn("⚠️ Regex parser failed, sending to OpenAI...");
                Optional<TradeSignal> aiParsed = aiParser.parse(body);
                if (aiParsed.isPresent()) {
                    TradeSignal s = aiParsed.get();
                    var plan = logic.buildPlan(s);
                    log.info("🤖 Parsed via AI (OpenAI): symbol={}, trigger={}, SL={}, targets={}",
                            s.symbol(), s.trigger(), s.stop(), s.targets());
                    log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                            plan.qty(), plan.tp(),
                            System.getProperty("trade.tp.percent", "5"),
                            plan.sl());
                } else {
                    log.error("❌ AI parser also failed, skipping this message.");
                }
            } else {
                log.debug("Not a trade signal (parser miss) and AI disabled.");
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