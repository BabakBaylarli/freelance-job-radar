package com.babakbaylarli.jobradar.source.telegram;

import it.tdlight.client.AuthenticationSupplier;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-time interactive login for the channel reader: {@code docker compose run --rm -it app --telegram-login}. Asks for
 * the code Telegram sends to the phone (and the 2FA password if set) on the terminal and stores the session in the
 * session directory (a Docker volume). Needs no database and does not start the application.
 */
public final class TelegramLoginCli {

    private TelegramLoginCli() {}

    /** @return process exit code: 0 logged in, 2 missing configuration, 1 failure */
    public static int run(Map<String, String> env) {
        String apiId = env.getOrDefault("TELEGRAM_API_ID", "");
        String apiHash = env.getOrDefault("TELEGRAM_API_HASH", "");
        String phone = env.getOrDefault("TELEGRAM_PHONE", "");
        String sessionDir = env.getOrDefault("TELEGRAM_SESSION_DIR", "/data/tdlight");
        if (apiId.isBlank() || apiHash.isBlank() || phone.isBlank()) {
            System.err.println("Set TELEGRAM_API_ID, TELEGRAM_API_HASH (from https://my.telegram.org) and TELEGRAM_PHONE (+countrycode...) in .env first.");
            return 2;
        }
        try {
            TdLightSupport.initNatives();
            var factory = TdLightSupport.newFactory();
            var client = factory.builder(TdLightSupport.settings(apiId, apiHash, sessionDir)).build(AuthenticationSupplier.user(phone.strip()));
            var me = client.getMeAsync().get(10, TimeUnit.MINUTES);
            System.out.println("Logged in as " + me.firstName + (me.lastName == null || me.lastName.isBlank() ? "" : " " + me.lastName)
                    + ". The session is stored in " + sessionDir + ". You can now enable TELEGRAM_CHANNELS_ENABLED=true.");
            client.closeAndWait();
            factory.close();
            return 0;
        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            return 1;
        }
    }
}
