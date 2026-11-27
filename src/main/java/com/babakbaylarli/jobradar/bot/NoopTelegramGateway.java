package com.babakbaylarli.jobradar.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Used when no bot token is configured: the app still polls and stores jobs, nothing is sent. */
@Component
@Conditional(TelegramConditions.BotMissing.class)
public class NoopTelegramGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(NoopTelegramGateway.class);

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public long sendHtml(long chatId, String html, Keyboard keyboard) {
        log.debug("Telegram disabled - dropping message");
        return 0;
    }

    @Override
    public void editKeyboard(long chatId, int messageId, Keyboard keyboard) {
        // nothing to do
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
        // nothing to do
    }
}
