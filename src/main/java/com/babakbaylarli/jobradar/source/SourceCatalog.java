package com.babakbaylarli.jobradar.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Every source the project knows, and what state it is in right now (active, switched off, or skipped). */
@Component
public class SourceCatalog {

    public enum Status { ACTIVE, DISABLED, SKIPPED }

    public record Entry(String id, String displayName, Status status, String reason) {}

    /** All known source ids, in the order they are shown. */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("weworkremotely", "We Work Remotely");
        KNOWN.put("remotive", "Remotive");
        KNOWN.put("remoteok", "RemoteOK");
        KNOWN.put("hackernews", "Hacker News");
        KNOWN.put("freelancer", "Freelancer.com");
        KNOWN.put("himalayas", "Himalayas");
        KNOWN.put("jobicy", "Jobicy");
        KNOWN.put("workingnomads", "Working Nomads");
        KNOWN.put("flru", "FL.ru");
        KNOWN.put("hh", "hh.ru");
        KNOWN.put("freelancehunt", "Freelancehunt");
        KNOWN.put("dou", "DOU");
        KNOWN.put("email", "Email alerts");
        KNOWN.put("telegram", "Telegram channels");
    }

    private final List<JobSource> active;

    public SourceCatalog(List<JobSource> active) {
        this.active = active;
    }

    public List<Entry> entries() {
        return KNOWN.entrySet().stream().map(known -> {
            Optional<JobSource> bean = active.stream().filter(s -> s.id().equals(known.getKey())).findFirst();
            if (bean.isEmpty()) {
                return new Entry(known.getKey(), known.getValue(), Status.DISABLED, null);
            }
            Optional<String> reason = bean.get().unavailableReason();
            return reason.map(r -> new Entry(known.getKey(), known.getValue(), Status.SKIPPED, r))
                    .orElseGet(() -> new Entry(known.getKey(), known.getValue(), Status.ACTIVE, null));
        }).toList();
    }

    /** Display name for a stored job source such as {@code remotive}, {@code email:upwork} or {@code telegram:somechannel}. */
    public static String displayName(String source) {
        int colon = source.indexOf(':');
        if (colon > 0) {
            String prefix = source.substring(0, colon);
            String detail = source.substring(colon + 1);
            return switch (prefix) {
                case "email" -> capitalize(detail) + " (email alert)";
                case "telegram" -> "Telegram @" + detail;
                default -> source;
            };
        }
        return KNOWN.getOrDefault(source, source);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
