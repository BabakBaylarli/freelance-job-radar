package com.babakbaylarli.jobradar.source.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "jobradar.sources.telegram-channels.enabled", havingValue = "true")
class TelegramChannelConfig {

    /** The TDLight client is created lazily on the first poll, so enabling the source never blocks start-up. */
    @Bean
    ChannelReader channelReader(TdLightSession session) {
        return new TdLightChannelReader(session);
    }
}
