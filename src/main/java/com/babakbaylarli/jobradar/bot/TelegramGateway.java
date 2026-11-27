package com.babakbaylarli.jobradar.bot;

/**
 * The few Telegram operations the app needs. Keeping them behind an interface lets the bot logic be tested
 * without Telegram, and lets the app run (collecting and storing jobs) when no bot token is configured.
 */
public interface TelegramGateway {

    /** {@code false} when no bot token is configured. */
    boolean enabled();

    /**
     * Send an HTML-formatted message (Telegram HTML subset). {@code keyboard} may be {@code null}.
     *
     * @return the Telegram id of the sent message (needed to recognise a reply such as "ok" to a draft)
     */
    long sendHtml(long chatId, String html, Keyboard keyboard);

    /** Replace the inline keyboard of an existing message. */
    void editKeyboard(long chatId, int messageId, Keyboard keyboard);

    /** Acknowledge a button press (shows a small toast to the user). */
    void answerCallback(String callbackQueryId, String text);
}
