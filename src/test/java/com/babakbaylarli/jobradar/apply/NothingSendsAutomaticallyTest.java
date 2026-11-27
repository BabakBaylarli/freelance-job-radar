package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tripwires for the project's central promise: nothing is sent without the owner's approval of that specific job. They scan
 * the production sources, so a future change that adds an automatic path fails the build and has to be a conscious decision.
 */
class NothingSendsAutomaticallyTest {

    private static final Path MAIN = Path.of("src/main/java/com/babakbaylarli/jobradar");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    @Test
    void sendersAreOnlyEverInvokedFromTheApprovalFlowOfApplicationService() throws IOException {
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            boolean callsSenders = Files.readString(file).contains("new SendRequest(");
            if (callsSenders) {
                assertThat(name).as("only ApplicationService may build a SendRequest").isEqualTo("ApplicationService.java");
            }
        }
    }

    @Test
    void noScheduledOrAsyncCodeExistsInTheApplyPackage() throws IOException {
        try (Stream<Path> files = Files.list(MAIN.resolve("apply"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                assertThat(content).as(file.getFileName() + " must not be scheduled").doesNotContain("@Scheduled");
                assertThat(content).as(file.getFileName() + " must not run on its own threads").doesNotContain("@Async").doesNotContain("Executors.");
            }
        }
    }

    @Test
    void theOnlyPublicWaysToReachASenderAreTheTwoApprovalMethods() {
        List<String> publicMethodsThatSend = java.util.Arrays.stream(ApplicationService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .filter(n -> n.equals("requestSend") || n.equals("confirmSend") || n.toLowerCase().contains("send") && !n.startsWith("attach"))
                .distinct().sorted().toList();

        assertThat(publicMethodsThatSend).containsExactly("confirmSend", "requestSend");
    }

    @Test
    void sendersNeverRetryOnTheirOwn() throws IOException {
        for (Path file : sources()) {
            if (file.getParent().getFileName().toString().equals("apply")) {
                assertThat(Files.readString(file)).as(file.getFileName() + " must not loop or retry sends").doesNotContain("maxAttempts").doesNotContain("retry(");
            }
        }
    }
}
