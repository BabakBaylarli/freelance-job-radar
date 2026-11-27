package com.babakbaylarli.jobradar.source.telegram;

import java.time.Instant;

/** One post of a public Telegram channel. {@code messageId} is the public id used in {@code t.me/<channel>/<id>} links. */
public record ChannelPost(String channel, long messageId, String text, Instant date) {

    public String link() {
        return "https://t.me/" + channel + "/" + messageId;
    }
}
