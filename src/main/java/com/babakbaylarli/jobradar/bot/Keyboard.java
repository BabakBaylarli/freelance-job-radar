package com.babakbaylarli.jobradar.bot;

import java.util.List;

/** Telegram-library-agnostic inline keyboard: rows of buttons. An empty keyboard removes the buttons. */
public record Keyboard(List<List<Button>> rows) {

    public static Keyboard empty() {
        return new Keyboard(List.of());
    }
}
