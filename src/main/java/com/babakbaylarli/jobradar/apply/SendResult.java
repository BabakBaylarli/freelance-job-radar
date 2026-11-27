package com.babakbaylarli.jobradar.apply;

/** Outcome of one send attempt. {@code responseId} is the platform's id of the created bid / message, if it returned one. */
public record SendResult(boolean ok, String responseId, String message) {

    public static SendResult ok(String responseId) {
        return new SendResult(true, responseId, null);
    }

    public static SendResult failed(String message) {
        return new SendResult(false, null, message);
    }
}
