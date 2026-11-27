package com.babakbaylarli.jobradar.source.telegram;

/** Sends one text message to one Telegram user from the owner's own account. Implemented on the official client API. */
public interface TelegramDirectMessenger {

    /** @return the sent message's id */
    String send(String username, String text) throws DirectMessageException;
}
