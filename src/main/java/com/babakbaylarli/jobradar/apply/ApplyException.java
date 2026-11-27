package com.babakbaylarli.jobradar.apply;

/** A problem the owner should be told about in plain words (nothing was sent). */
public class ApplyException extends RuntimeException {

    public ApplyException(String message) {
        super(message);
    }
}
