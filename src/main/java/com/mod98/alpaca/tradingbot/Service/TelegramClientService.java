package com.mod98.alpaca.tradingbot.Service;

import com.mod98.alpaca.tradingbot.Config.TelegramProperties;
import com.mod98.alpaca.tradingbot.Model.AppSettings;
import com.mod98.alpaca.tradingbot.Parsing.AiSignalParser;
import com.mod98.alpaca.tradingbot.Parsing.SignalParser;
import com.mod98.alpaca.tradingbot.Parsing.TradeSignal;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    private final Map<Long, TradeSignal> activeSignals = new ConcurrentHashMap<>();

    private final TelegramProperties props;
    private final SettingsService settings;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    private AiSignalParser aiParser;
    private boolean aiAvailable = false;
    //private TradeLogic logic;

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
            builder.addUpdateHandler(TdApi.UpdateMessageEdited.class, this::onMessageEdited);
            builder.addUpdateHandler(TdApi.UpdateMessageContent.class, this::onMessageContent);
            builder.addUpdateHandler(TdApi.UpdateDeleteMessages.class, this::onDeleteMessages);

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
        // 3 add ID For message
        long msgId = update.message.id;

        if (appSettings.getChannelId() == null || !Objects.equals(chatId, appSettings.getChannelId())){
            return;
        }

        // 3 Text Only
        if (!(update.message.content instanceof TdApi.MessageText text)) return;
        String body = text.text.text;
        if (body == null || body.isBlank()) return;
        log.info("Incoming message [chatId={}, msgId={}]:\n{}", chatId, msgId, body);

        // 4 Build TradeLogic from existing DB values
        TradeLogic logic = new TradeLogic(appSettings.getFixedBudget(), appSettings.getTpPercent());

        // 5 Regex if Activated
        if (appSettings.isRegexEnabled()) {
            Optional<TradeSignal> parsed = SignalParser.parse(body);
            if (parsed.isPresent()){
                TradeSignal sig = parsed.get();
                activeSignals.put(msgId, sig);
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
                activeSignals.put(msgId, sig);
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

private void onMessageEdited(TdApi.UpdateMessageEdited upd) {
        AppSettings app = settings.get();
        if (!Objects.equals(upd.chatId, app.getChannelId()))
            return;
        log.info("Message edited: chatId={}, messageId={}, editDate={}",
                upd.chatId, upd.messageId, upd.editDate);
    }

    private void onMessageContent(TdApi.UpdateMessageContent upd) {
        AppSettings app = settings.get();
        if (!Objects.equals(upd.chatId, app.getChannelId())) return;

        long msgId = upd.messageId;
        if (!(upd.newContent instanceof TdApi.MessageText txt)) return;
        String body = txt.text.text;
        if (body == null || body.isBlank()) return;
        log.info("Edited content received [chatId={}, msgId={}]:\n{}", upd.chatId, upd.messageId, body);

        TradeLogic logic = new TradeLogic(app.getFixedBudget(), app.getTpPercent());

        if (activeSignals.containsKey(msgId)) {
            activeSignals.remove(msgId);
            log.info("❌ Canceled old plan for messageId={}", msgId);
        }

        Optional<TradeSignal> parsed = SignalParser.parse(body);
        if (parsed.isPresent()) {
            TradeSignal sig = parsed.get();
            activeSignals.put(msgId, sig);
            var plan = logic.buildPlan(sig);
            log.info("✅ REGEX(EDIT): symbol={}, trigger={}, SL={}, targets={}",
                    sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
            log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                    plan.qty(), plan.tp(), app.getTpPercent(), plan.sl());
            return;
        } else {
            log.warn("Regex parser failed on edited message.");
        }
        if (app.isAiEnabled() && aiAvailable && aiParser != null) {
            Optional<TradeSignal> aiParsed = aiParser.parse(body);
            if (aiParsed.isPresent()) {
                TradeSignal sig = aiParsed.get();
                activeSignals.put(msgId, sig);
                var plan = logic.buildPlan(sig);
                log.info("🤖 AI(EDIT): symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), app.getTpPercent(), plan.sl());
            } else {
                log.error("AI parser failed on edited message.");
            }
        }
    }

    private void onDeleteMessages(TdApi.UpdateDeleteMessages upd) {
        AppSettings app = settings.get();
        if (!Objects.equals(upd.chatId, app.getChannelId())) return;
        if (upd.isPermanent) {
            for (long mid : upd.messageIds) {
                if (activeSignals.remove(mid) != null) {
                    log.info("🗑️ Removed plan due to message deletion: {}", mid);
                }
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