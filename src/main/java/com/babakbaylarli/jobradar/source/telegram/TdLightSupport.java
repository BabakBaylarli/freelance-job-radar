package com.babakbaylarli.jobradar.source.telegram;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.client.APIToken;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared TDLight setup for the running source and the one-time login command, so both use the same session files. */
final class TdLightSupport {

    private TdLightSupport() {}

    static void initNatives() throws ChannelReaderException {
        try {
            Init.init();
            Log.setVerbosityLevel(1); // TDLib's own logging is very chatty
        } catch (Exception | LinkageError e) {
            throw new ChannelReaderException("The TDLight native library could not be loaded on this platform: " + e.getMessage(), e);
        }
    }

    static TDLibSettings settings(String apiId, String apiHash, String sessionDir) throws ChannelReaderException {
        int id;
        try {
            id = Integer.parseInt(apiId.strip());
        } catch (NumberFormatException e) {
            throw new ChannelReaderException("TELEGRAM_API_ID must be a number (get it at my.telegram.org)");
        }
        TDLibSettings settings = TDLibSettings.create(new APIToken(id, apiHash.strip()));
        Path dir = Path.of(sessionDir);
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new ChannelReaderException("Cannot create the Telegram session directory " + dir + ": " + e.getMessage(), e);
        }
        settings.setDatabaseDirectoryPath(dir.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(dir.resolve("downloads"));
        settings.setFileDatabaseEnabled(false);          // we only read text, never download media
        settings.setMessageDatabaseEnabled(false);
        settings.setApplicationVersion("freelance-job-radar");
        return settings;
    }

    static SimpleTelegramClientFactory newFactory() {
        return new SimpleTelegramClientFactory();
    }
}
