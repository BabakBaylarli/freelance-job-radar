package com.babakbaylarli.jobradar.apply;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the contact a posting itself names: an e-mail address, or a Telegram {@code @username}. */
public final class ContactFinder {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern TELEGRAM = Pattern.compile("(?<![\\w@.])@([A-Za-z][A-Za-z0-9_]{4,31})\\b(?![\\w.]*@)");
    private static final java.util.List<String> NO_REPLY = java.util.List.of("noreply", "no-reply", "donotreply", "do-not-reply", "mailer-daemon", "unsubscribe");
    private static final java.util.List<String> NOT_MAIL = java.util.List.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp");

    private ContactFinder() {}

    /** First plausible e-mail address in the text that is not a no-reply address or the owner's own address. */
    public static Optional<String> email(String text, String ownAddress) {
        Matcher m = EMAIL.matcher(text == null ? "" : text);
        while (m.find()) {
            String address = m.group().toLowerCase(Locale.ROOT).replaceAll("[.]+$", "");
            if (NO_REPLY.stream().anyMatch(address::contains) || NOT_MAIL.stream().anyMatch(address::endsWith)
                    || (ownAddress != null && address.equalsIgnoreCase(ownAddress.strip()))) {
                continue;
            }
            return Optional.of(address);
        }
        return Optional.empty();
    }

    /** First Telegram username in the text that is not the channel itself and does not look like a bot. */
    public static Optional<String> telegramUsername(String text, String channelUsername) {
        Matcher m = TELEGRAM.matcher(text == null ? "" : text);
        while (m.find()) {
            String name = m.group(1);
            if (name.equalsIgnoreCase(channelUsername) || name.toLowerCase(Locale.ROOT).endsWith("bot")) {
                continue;
            }
            return Optional.of(name);
        }
        return Optional.empty();
    }
}
