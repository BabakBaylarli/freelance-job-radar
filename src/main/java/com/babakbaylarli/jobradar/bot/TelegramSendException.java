package com.babakbaylarli.jobradar.bot;

public class TelegramSendException extends RuntimeException {

    public TelegramSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
