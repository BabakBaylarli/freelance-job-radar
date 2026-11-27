package com.babakbaylarli.jobradar.source;

/** The market a job belongs to; the owner can switch markets on and off with /markets. */
public enum Market {
    INTERNATIONAL("🌍", "International"),
    RU("🇷🇺", "Russia"),
    UA("🇺🇦", "Ukraine"),
    AZ("🇦🇿", "Azerbaijan");

    private final String flag;
    private final String label;

    Market(String flag, String label) {
        this.flag = flag;
        this.label = label;
    }

    public String flag() {
        return flag;
    }

    public String label() {
        return label;
    }
}
