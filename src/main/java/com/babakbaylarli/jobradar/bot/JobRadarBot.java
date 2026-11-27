package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Long-polling entry point registered by {@code telegrambots-springboot-longpolling-starter}. It only translates
 * Telegram updates into plain calls on {@link BotUpdateHandler}. Only exists when a bot token is configured.
 */
@Component
@Conditional(TelegramConditions.BotConfigured.class)
public class JobRadarBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobRadarBot.class);

    private final String token;
    private final BotUpdateHandler handler;

    public JobRadarBot(JobRadarProperties properties, BotUpdateHandler handler) {
        this.token = properties.telegram().botToken();
        this.handler = handler;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingSingleThreadUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                CallbackQuery query = update.getCallbackQuery();
                if (query.getMessage() != null) {
                    handler.onCallback(query.getMessage().getChatId(), query.getMessage().getMessageId(), query.getId(), query.getData());
                }
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                var reply = update.getMessage().getReplyToMessage();
                handler.onMessage(update.getMessage().getChatId(), update.getMessage().getText(), reply == null ? null : (long) reply.getMessageId());
            }
        } catch (RuntimeException e) {
            log.error("event=update_failed update_id={}", update.getUpdateId(), e);
        }
    }
}
