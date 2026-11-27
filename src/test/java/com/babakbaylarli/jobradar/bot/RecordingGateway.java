package com.babakbaylarli.jobradar.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double: records everything the bot would have sent to Telegram. */
public class RecordingGateway implements TelegramGateway {

    public record Sent(long chatId, String html, Keyboard keyboard) {}

    public record Edit(long chatId, int messageId, Keyboard keyboard) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final List<Edit> edits = new CopyOnWriteArrayList<>();
    public final List<String> answers = new CopyOnWriteArrayList<>();
    public boolean enabled = true;
    public boolean failSends;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void sendHtml(long chatId, String html, Keyboard keyboard) {
        if (failSends) {
            throw new TelegramSendException("simulated Telegram outage", null);
        }
        sent.add(new Sent(chatId, html, keyboard));
    }

    @Override
    public void editKeyboard(long chatId, int messageId, Keyboard keyboard) {
        edits.add(new Edit(chatId, messageId, keyboard));
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
        answers.add(text);
    }

    public List<String> texts() {
        return new ArrayList<>(sent.stream().map(Sent::html).toList());
    }
}
