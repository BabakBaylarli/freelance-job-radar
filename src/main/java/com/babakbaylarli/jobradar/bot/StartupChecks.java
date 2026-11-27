package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.ai.ProfileLoader;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs one clear line per missing optional setting so a half-configured start is easy to diagnose. */
@Component
public class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final JobRadarProperties properties;
    private final ProfileLoader profile;

    public StartupChecks(JobRadarProperties properties, ProfileLoader profile) {
        this.properties = properties;
        this.profile = profile;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        var telegram = properties.telegram();
        if (!telegram.botConfigured()) {
            log.warn("TELEGRAM_BOT_TOKEN is not set: the Telegram bot is DISABLED. Jobs are still polled and stored; "
                    + "create a bot with @BotFather and set the token in .env to receive them.");
        } else if (telegram.ownerChatId() == null) {
            log.warn("TELEGRAM_OWNER_CHAT_ID is not set: the bot runs in setup mode. Send /start to your bot - it replies "
                    + "with your chat id - then set TELEGRAM_OWNER_CHAT_ID and restart.");
        }
        if (properties.matching().include().isEmpty()) {
            log.warn("No include keywords configured (config/keywords.yml): no job can match.");
        }
        if (!profile.exists()) {
            log.warn("Profile file {} not found: proposals cannot be drafted until you create it (copy config/profile.example.md).",
                    profile.path().toAbsolutePath());
        }
    }
}
