package com.babakbaylarli.jobradar.ai;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reads {@code profile.md} on every draft, so edits take effect without restarting the app. */
@Component
public class ProfileLoader {

    private final Path path;

    public ProfileLoader(JobRadarProperties properties) {
        this.path = Path.of(properties.profilePath());
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.isRegularFile(path);
    }

    public Optional<String> load() {
        try {
            if (!exists()) {
                return Optional.empty();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
