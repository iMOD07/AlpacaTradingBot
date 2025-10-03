package com.mod98.alpaca.tradingbot.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ChannelResolver {
    private static final Logger log = LoggerFactory.getLogger(ChannelResolver.class);

    public Long resolveUsername(SimpleTelegramClient client, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String uname = raw.trim();
        if (uname.startsWith("@")) uname = uname.substring(1);
        String finalUname = uname;

        CompletableFuture<TdApi.Chat> fut = client.send(new TdApi.SearchPublicChat(finalUname));
        TdApi.Chat chat = fut.join();
        if (chat != null) {
            log.info("Resolved @{} -> {}", finalUname, chat.id);
            return chat.id;
        } else {
            log.warn("Could not resolve @{}", finalUname);
            return null;
        }
    }

    public Map<String, Long> resolveUsernames(SimpleTelegramClient client, Iterable<String> usernames) {
        Map<String, Long> out = new HashMap<>();
        if (usernames == null) return out;
        for (String u : usernames) {
            Long id = resolveUsername(client, u);
            if (id != null) out.put(u.toLowerCase(Locale.ROOT), id);
        }
        return out;
    }
}
