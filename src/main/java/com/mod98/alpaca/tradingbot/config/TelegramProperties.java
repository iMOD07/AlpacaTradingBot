package com.mod98.alpaca.tradingbot.config;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "telegram")
@Validated
@Getter
@Setter
public class TelegramProperties {

    @NonNull
    private Integer apiId;

    @NonNull
    private String apiHash;

    @NonNull
    private String phone;

    private String sessionDir = "./tdlight-session";

    //To read from application.properties
    @NonNull
    private ChannelTelegram channelTelegram = new ChannelTelegram();

    @Getter
    @Setter
    public static class ChannelTelegram {
        @NonNull
        private Long id; // Spring links it from telegram.channel.id
    }
}
