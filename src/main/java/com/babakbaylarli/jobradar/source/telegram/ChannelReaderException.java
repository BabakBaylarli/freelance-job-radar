package com.babakbaylarli.jobradar.source.telegram;

public class ChannelReaderException extends Exception {

    public ChannelReaderException(String message) {
        super(message);
    }

    public ChannelReaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
