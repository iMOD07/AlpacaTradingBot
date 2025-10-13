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
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    private final Map<String, TradeSignal> activeSignals = new ConcurrentHashMap<>();
    private static String key(long chatId, long msgId) { return chatId + "|" + msgId; }

    private final TelegramProperties props;
    private final SettingsService settings;
    private final TradeExecutorService executor;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    private final TradeAuditService audit;

    private AiSignalParser aiParser;
    private boolean aiAvailable = false;

    public TelegramClientService(TelegramProperties props, SettingsService settings, TradeExecutorService executor, TradeAuditService audit) {
        this.props = props;
        this.settings = settings;
        this.executor = executor;
        this.audit = audit;
    }

    @PostConstruct
    public void initAndStart() {
        AppSettings appSettings = settings.get();

        if (appSettings.isAiEnabled()) {
            String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY",
                    System.getProperty("openai.api.key", ""));
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("AI enabled but missing OPENAI_API_KEY, disabling AI parser.");
                aiAvailable = false;
            } else {
                this.aiParser = new AiSignalParser(apiKey);
                aiAvailable = true;
                log.info("🤖 AI Parser enabled.");
            }
        } else {
            aiAvailable = false;
        }

        log.info("Config from DB → Regex={}, AI={}, Budget=${}, TP%={}, sessionDir={}, extendedHours={}",
                appSettings.isRegexEnabled(), appSettings.isAiEnabled(),
                appSettings.getFixedBudget(), appSettings.getTpPercent(),
                appSettings.getSessionDir(), appSettings.isAlpacaExtendedHours());

        // ===== TDLight =====
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

            log.info("TDLight client is ready. Listening to ALL text messages (including forwarded).");
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
        long chatId = update.message.chatId;
        long msgId  = update.message.id;

        if (!(update.message.content instanceof TdApi.MessageText text)) return;
        String body = text.text.text;
        if (body == null || body.isBlank()) return;

        if (update.message.forwardInfo != null) {
            log.info("Forwarded message (chatId={}, msgId={})", chatId, msgId);
        }

        log.info("Incoming message [chatId={}, msgId={}]:\n{}", chatId, msgId, body);

        AppSettings appSettings = settings.get();
        TradeLogic logic = new TradeLogic(appSettings.getFixedBudget(), appSettings.getTpPercent());

        // 1 First Regex
        if (appSettings.isRegexEnabled()) {
            Optional<TradeSignal> parsed = SignalParser.parse(body);
            if (parsed.isPresent()) {
                TradeSignal sig = parsed.get();
                activeSignals.put(key(chatId, msgId), sig);
                var plan = logic.buildPlan(sig);
                log.info("✅ REGEX: symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), appSettings.getTpPercent(), plan.sl());

                executor.executeSignal(
                        sig, plan.qty(), appSettings.getTpPercent(), appSettings.isAlpacaExtendedHours()
                );
                return;
            } else {
                log.warn("Regex parser failed for this message.");
            }
        }

        // 2 - Sec AI
        if (appSettings.isAiEnabled() && aiAvailable && aiParser != null) {
            Optional<TradeSignal> aiParsed = aiParser.parse(body);
            if (aiParsed.isPresent()) {
                TradeSignal sig = aiParsed.get();
                activeSignals.put(key(chatId, msgId), sig);
                var plan = logic.buildPlan(sig);
                log.info("🤖 AI: symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), appSettings.getTpPercent(), plan.sl());

                executor.executeSignal(
                        sig, plan.qty(), appSettings.getTpPercent(), appSettings.isAlpacaExtendedHours()
                );
                return;
            } else {
                log.error("AI parser failed as well — skipping this message.");
            }
        }

        // If the analyses are wrong, save in DB.
        String shortBody = body.length() > 180 ? body.substring(0, 180) + "..." : body;
        audit.record(null, "PARSE_FAILED", "body=" + shortBody);
    }


    private void onMessageEdited(TdApi.UpdateMessageEdited upd) {
        log.info("Message edited: chatId={}, messageId={}, editDate={}",
                upd.chatId, upd.messageId, upd.editDate);
    }

    private void onMessageContent(TdApi.UpdateMessageContent upd) {
        long chatId = upd.chatId;
        long msgId  = upd.messageId;

        if (!(upd.newContent instanceof TdApi.MessageText txt)) return;
        String body = txt.text.text;
        if (body == null || body.isBlank()) return;

        log.info("Edited content received [chatId={}, msgId={}]:\n{}", chatId, msgId, body);

        AppSettings app = settings.get();
        TradeLogic logic = new TradeLogic(app.getFixedBudget(), app.getTpPercent());

        String k = key(chatId, msgId);
        if (activeSignals.containsKey(k)) {
            activeSignals.remove(k);
            log.info("❌ Canceled old plan for message {}", k);
        }

        // 1- Regex
        Optional<TradeSignal> parsed = SignalParser.parse(body);
        if (parsed.isPresent()) {
            TradeSignal sig = parsed.get();
            activeSignals.put(k, sig);
            var plan = logic.buildPlan(sig);
            log.info("✅ REGEX(EDIT): symbol={}, trigger={}, SL={}, targets={}",
                    sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
            log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                    plan.qty(), plan.tp(), app.getTpPercent(), plan.sl());

            executor.executeSignal(
                    sig, plan.qty(), app.getTpPercent(), app.isAlpacaExtendedHours()
            );
            return;
        } else {
            log.warn("Regex parser failed on edited message.");
        }

        // 2 - AI
        if (app.isAiEnabled() && aiAvailable && aiParser != null) {
            Optional<TradeSignal> aiParsed = aiParser.parse(body);
            if (aiParsed.isPresent()) {
                TradeSignal sig = aiParsed.get();
                activeSignals.put(k, sig);
                var plan = logic.buildPlan(sig);
                log.info("🤖 AI(EDIT): symbol={}, trigger={}, SL={}, targets={}",
                        sig.symbol(), sig.trigger(), sig.stop(), sig.targets());
                log.info("Plan: qty={}, TP={} (+{}%), SL={}",
                        plan.qty(), plan.tp(), app.getTpPercent(), plan.sl());

                executor.executeSignal(
                        sig, plan.qty(), app.getTpPercent(), app.isAlpacaExtendedHours()
                );
                return;
            } else {
                log.error("AI parser failed on edited message.");
            }
        }

        String shortBody = body.length() > 180 ? body.substring(0, 180) + "..." : body;
        audit.record(null, "PARSE_FAILED_EDIT", "body=" + shortBody);
    }


    private void onDeleteMessages(TdApi.UpdateDeleteMessages upd) {
        if (upd.isPermanent) {
            for (long mid : upd.messageIds) {
                String k = key(upd.chatId, mid);
                if (activeSignals.remove(k) != null) {
                    log.info("🗑️ Removed plan due to message deletion: {}", k);
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
