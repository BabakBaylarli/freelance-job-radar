package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Talks to the real Telegram Bot API (only created when a bot token is configured). */
@Component
@Conditional(TelegramConditions.BotConfigured.class)
public class TelegramClientGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientGateway.class);

    private final TelegramClient client;

    public TelegramClientGateway(JobRadarProperties properties) {
        this.client = new OkHttpTelegramClient(properties.telegram().botToken());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void sendHtml(long chatId, String html, Keyboard keyboard) {
        SendMessage.SendMessageBuilder<?, ?> message = SendMessage.builder()
                .chatId(chatId)
                .text(html)
                .parseMode("HTML")
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build());
        if (keyboard != null && !keyboard.rows().isEmpty()) {
            message.replyMarkup(toMarkup(keyboard));
        }
        try {
            client.execute(message.build());
        } catch (TelegramApiException e) {
            throw new TelegramSendException("Telegram sendMessage failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void editKeyboard(long chatId, int messageId, Keyboard keyboard) {
        try {
            client.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .replyMarkup(toMarkup(keyboard))
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Could not edit keyboard: {}", e.getMessage()); // e.g. "message is not modified"
        }
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).build());
        } catch (TelegramApiException e) {
            log.debug("Could not answer callback: {}", e.getMessage());
        }
    }

    /** Show the command menu in Telegram's UI. Failure is harmless. */
    @EventListener(ApplicationReadyEvent.class)
    void registerCommands() {
        try {
            client.execute(SetMyCommands.builder().commands(List.of(
                    new BotCommand("digest", "Top unseen matches right now"),
                    new BotCommand("saved", "Jobs you saved"),
                    new BotCommand("stats", "Jobs seen/matched per source, last 7 days"),
                    new BotCommand("keywords", "Show the current matching config"),
                    new BotCommand("pause", "Stop notifications"),
                    new BotCommand("resume", "Resume notifications"),
                    new BotCommand("help", "What this bot can do"))).build());
        } catch (TelegramApiException e) {
            log.warn("Could not register the bot command menu: {}", e.getMessage());
        }
    }

    private static InlineKeyboardMarkup toMarkup(Keyboard keyboard) {
        List<InlineKeyboardRow> rows = keyboard.rows().stream()
                .map(row -> new InlineKeyboardRow(row.stream()
                        .map(b -> InlineKeyboardButton.builder().text(b.text()).callbackData(b.callbackData()).build())
                        .toList()))
                .toList();
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
