package com.babakbaylarli.jobradar.matching;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Canonical form of a job URL so the same posting is recognized even if tracking parameters differ. */
public final class UrlNormalizer {

    private UrlNormalizer() {}

    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.strip();
        try {
            URI uri = new URI(trimmed);
            if (uri.getHost() == null) {
                return trimmed;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = null;
            if (uri.getRawQuery() != null) {
                query = Arrays.stream(uri.getRawQuery().split("&"))
                        .filter(p -> !p.isEmpty() && !isTracking(p))
                        .collect(Collectors.joining("&"));
                if (query.isEmpty()) {
                    query = null;
                }
            }
            StringBuilder sb = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT))
                    .append("://")
                    .append(uri.getHost().toLowerCase(Locale.ROOT));
            if (uri.getPort() != -1) {
                sb.append(':').append(uri.getPort());
            }
            sb.append(path);
            if (query != null) {
                sb.append('?').append(query);
            }
            return sb.toString(); // fragment dropped on purpose
        } catch (Exception e) {
            return trimmed;
        }
    }

    private static boolean isTracking(String param) {
        String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return name.startsWith("utm_") || name.equals("ref") || name.equals("fbclid") || name.equals("gclid");
    }
}
