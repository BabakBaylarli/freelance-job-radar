package com.babakbaylarli.jobradar.source.telegram;

import java.util.List;

/** Reads the latest posts of public Telegram channels. The production implementation uses the official client API. */
public interface ChannelReader extends AutoCloseable {

    /** Newest posts first. Never joins, subscribes or posts anything. */
    List<ChannelPost> latestPosts(String username, int limit) throws ChannelReaderException;

    @Override
    default void close() {}
}
