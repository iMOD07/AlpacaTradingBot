package com.mod98.alpaca.tradingbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "telegram")
@Getter
@Setter
public class TelegramProperties {
    private Integer apiId;
    private String apiHash;
    private String phone;
    private String sessionDir = "./tdlight-session";

    // Optional: Channel names like @MyChannel (for later use)
    private List<String> channels;

    //To support "telegram.channel.id=-2981121586"
    private Channel channel = new Channel();

    @Getter @Setter
    public static class Channel {
        private Long id; // Spring links it from telegram.channel.id
    }
}
