package com.babakbaylarli.jobradar.source.telegram;

public class DirectMessageException extends Exception {

    public DirectMessageException(String message) {
        super(message);
    }

    public DirectMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
