package com.babakbaylarli.jobradar.source.telegram;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "jobradar.sources.telegram-channels.enabled", havingValue = "true")
class TelegramChannelConfig {

    /** The TDLight client is created lazily on the first poll, so enabling the source never blocks start-up. */
    @Bean
    ChannelReader channelReader(JobRadarProperties properties) {
        return new TdLightChannelReader(properties.sources().telegramChannels());
    }
}
