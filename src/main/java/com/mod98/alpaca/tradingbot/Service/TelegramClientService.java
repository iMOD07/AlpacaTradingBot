package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Config.TelegramProperties;
import com.mod98.alpaca.tradingbot.Config.TradeProperties;
import com.mod98.alpaca.tradingbot.Model.AppSettings;
import com.mod98.alpaca.tradingbot.Parsing.AiSignalParser;
import com.mod98.alpaca.tradingbot.Parsing.SignalParser;
import com.mod98.alpaca.tradingbot.Parsing.TradeSignal;
import com.mod98.alpaca.tradingbot.Service.SettingsService;
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
    private final SettingsService settings;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    private AiSignalParser aiParser;
    private boolean aiAvailable = false;
    private TradeLogic logic;

    public TelegramClientService(TelegramProperties props, SettingsService settings) {
        this.props = props;
        this.settings = settings;
    }

    @PostConstruct
    public void initAndStart() {
        // Read Settings from DB
        AppSettings appSettings = settings.get();
        if (appSettings.getChannelId() == null) {
            throw new IllegalStateException("app_settings.channelId is missing in DB!");
        }

        // AI Parser
        if (appSettings.isAiEnabled()) {
            String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY",
                    System.getProperty("openai.api.key", ""));
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("AI enabled but missing OPENAI_API_KEY, then disabling AI parser.");
                aiAvailable = false;
            } else {
                this.aiParser = new AiSignalParser(apiKey);
                aiAvailable = true;
                log.info("🤖 AI Parser enabled 🤖.");
            }
        } else {
            aiAvailable = false;
        }
        log.info("Config from DB → Regex={}, AI={}, Budget=${}, TP%={}, channelId={}, sessionDir={}",
                appSettings.isRegexEnabled(), appSettings.isAiEnabled(), appSettings.getFixedBudget(), appSettings.getTpPercent(),
                appSettings.getChannelId(), appSettings.getSessionDir());

        // ===== TDLight start =====
        try {
            APIToken apiToken = new APIToken(props.getApiId(), props.getApiHash());
            TDLibSettings td = TDLibSettings.create(apiToken);
            Path base = Paths.get(appSettings.getSessionDir());
            td.setDatabaseDirectoryPath(base.resolve("db"));
            td.setDownloadedFilesDirectoryPath(base.resolve("files"));

            clientFactory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = clientFactory.builder(td);

            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);

            AuthenticationSupplier<?> auth = AuthenticationSupplier.user(props.getPhone());
            client = builder.build(auth);

            log.info("TDLight client is ready. Watching chatId={}", appSettings.getChannelId());
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

        // 1 Read Last Settings from Database
        AppSettings appSettings = settings.get();

        // 2 Filter Channel Telegram from DB
        long chatId = update.message.chatId;
        if (appSettings.getChannelId() == null || !Objects.equals(chatId, appSettings.getChannelId())){
            return;
        }

        // 3 Text Only
        if (!(update.message.content instanceof TdApi.MessageText text)) return;
        String body = text.text.text;
        if (body == null || body.isBlank()) return;
        log.info("Incoming message [{}]:\n{}", chatId, body);

        // 4 Build TradeLogic from existing DB values
        TradeLogic logic = new TradeLogic(appSettings.getFixedBudget(), appSettings.getTpPercent());

        // 5 Regex if Activated
        if (appSettings.isRegexEnabled()) {
            Optional<TradeSignal> parsed = SignalParser.parse(body);
            if (parsed.isPresent()){
                TradeSignal sig = parsed.get();
                var plan = logic.buildPlan(sig);
                log.info("✅ REGEX: symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), appSettings.getTpPercent(), plan.sl());
                return;
            } else {
                log.warn("Regex parser failed for this message.");
            }
        }

        // 6 AI if Activated
        if (appSettings.isAiEnabled() && aiAvailable && aiParser != null) {
            Optional<TradeSignal> aiParsed = aiParser.parse(body);
            if (aiParsed.isPresent()) {
                TradeSignal sig = aiParsed.get();
                var plan = logic.buildPlan(sig);
                log.info("🤖 AI: symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), appSettings.getTpPercent(), plan.sl());
            } else {
                log.error("AI parser failed as well — skipping this message.");
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