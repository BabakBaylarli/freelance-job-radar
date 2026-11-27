package com.babakbaylarli.jobradar.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Guards the Docker Compose file: the default stack must stay simple and private, the Tailscale add-on strictly optional. */
class DeploymentFilesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compose() throws IOException {
        return new Yaml().load(Files.readString(Path.of("docker-compose.yml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) compose().get("services")).get(name);
    }

    @Test
    void tailscaleIsAnOptInProfileSoTheDefaultStackNeverStartsItOrNeedsItsSettings() throws IOException {
        assertThat(service("tailscale").get("profiles")).isEqualTo(List.of("tailscale"));
        assertThat(service("app").get("profiles")).isNull();
        assertThat(service("postgres").get("profiles")).isNull();
    }

    @Test
    void noVariableIsMandatoryBecauseComposeInterpolatesInactiveProfilesToo() throws IOException {
        // "${VAR:?message}" would make *every* docker compose command fail for people who do not use that service
        assertThat(Files.readString(Path.of("docker-compose.yml"))).doesNotContain(":?");
    }

    @Test
    void theAppIsNeverPublishedOnAPublicInterface() throws IOException {
        @SuppressWarnings("unchecked")
        List<String> ports = (List<String>) service("app").get("ports");

        assertThat(ports).isNotEmpty().allSatisfy(port -> assertThat(port).startsWith("127.0.0.1:"));
        assertThat(service("postgres").get("ports")).isNull(); // the database is not published at all
        assertThat(service("tailscale").get("ports")).isNull(); // Tailscale needs no published port
    }

    @Test
    void tailscaleRunsInUserspaceWithoutExtraPrivileges() throws IOException {
        Map<String, Object> tailscale = service("tailscale");
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) tailscale.get("environment");

        assertThat(env.get("TS_USERSPACE")).isEqualTo("true");
        assertThat(tailscale).doesNotContainKeys("cap_add", "devices", "privileged", "network_mode");
    }

    @Test
    void theServeConfigForwardsTheTailnetPortToTheAppOnly() throws IOException {
        JsonNode serve = new ObjectMapper().readTree(Files.readString(Path.of("deploy/tailscale-serve.json")));

        assertThat(serve.path("TCP").fieldNames()).toIterable().containsExactly("8080");
        assertThat(serve.path("TCP").path("8080").path("TCPForward").asText()).isEqualTo("app:8080");
    }

    @Test
    void theEnvTemplateDocumentsTheTailscaleSettingsWithoutAKey() throws IOException {
        String env = Files.readString(Path.of(".env.example"));

        assertThat(env).contains("TS_AUTHKEY=\n").contains("TS_HOSTNAME=job-radar");
    }
}
