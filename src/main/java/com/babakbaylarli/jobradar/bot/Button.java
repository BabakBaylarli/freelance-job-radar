package com.babakbaylarli.jobradar.bot;

/** An inline keyboard button. {@code callbackData} is limited to 64 bytes by Telegram. */
public record Button(String text, String callbackData) {}
