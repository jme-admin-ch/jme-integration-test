package ch.admin.bit.jeap.jme.test;

import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.ActiveProfilesResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/**
 * Base class for integration tests that start Spring Boot services via Maven.
 * Provides common plumbing for profile resolution, service lifecycle management,
 * and health check polling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
public abstract class BootServiceIntegrationTestBase {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().getParent();
    private static final String MVN = PROJECT_ROOT.resolve("mvnw").toString();
    private static final Duration SERVICE_STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static final List<Process> startedServices = new ArrayList<>();

    @BeforeAll
    static void setDefaults() {
        Awaitility.setDefaultTimeout(Duration.ofSeconds(60));
        Awaitility.setDefaultPollInterval(Duration.ofSeconds(1));
    }

    @AfterAll
    static void stopServices() {
        log.info("Stopping services...");
        startedServices.forEach(BootServiceIntegrationTestBase::stopProcessTree);
        startedServices.clear();
    }

    protected static void startService(String moduleName, String baseUrl) throws IOException {
        log.info("Starting {}...", moduleName);
        Process process = startMavenService(moduleName, TestProfileResolver.profile());
        startedServices.addFirst(process);
        var healthUrl = baseUrl + "/actuator/health/readiness";
        waitForService(healthUrl, SERVICE_STARTUP_TIMEOUT);
        log.info("{} is ready.", moduleName);
    }

    private static Process startMavenService(String moduleName, String springProfile) throws IOException {
        List<String> cmds = new ArrayList<>();
        cmds.add(MVN);
        if (TestProfileResolver.isCI()) {
            log.info("Running on CI, using workspace-local maven settings file");
            cmds.add("-s");
            cmds.add("settings.xml");
        }
        cmds.addAll(List.of(
                "--projects", moduleName,
                "spring-boot:run",
                "-Dspring-boot.run.profiles=" + springProfile));

        ProcessBuilder pb = new ProcessBuilder(cmds);
        pb.directory(PROJECT_ROOT.toFile());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Thread outputThread = createStdIoCopyThread(moduleName, process);
        outputThread.start();

        return process;
    }

    private static Thread createStdIoCopyThread(String moduleName, Process process) {
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info(line);
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    log.error("Error reading output from {}", moduleName, e);
                }
            }
        }, moduleName);
        outputThread.setDaemon(true);
        return outputThread;
    }

    protected static void waitForService(String healthUrl, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .pollDelay(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> checkHealth(healthUrl, null, null));
    }

    private static boolean checkHealth(String healthUrl, String username, String password) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        if (username != null && password != null) {
            String encoded = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }
        try {
            return conn.getResponseCode() == 200;
        } finally {
            conn.disconnect();
        }
    }

    private static void stopProcessTree(Process process) {
        process.descendants().forEach(processHandle -> {
            log.info("Stopping child process " + processHandle.pid());
            processHandle.destroyForcibly();
        });
        log.info("Stopping process " + process.pid());
        process.destroyForcibly();
    }

    protected String fetchAccessToken(String authBaseUrl, String clientId, String secret) {
        return given()
                .baseUri(authBaseUrl)
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", clientId)
                .formParam("client_secret", secret)
                .when()
                .post("/oauth2/token")
                .then()
                .statusCode(200)
                .extract()
                .path("access_token");
    }

    @SuppressWarnings("NullableProblems")
    public static class TestProfileResolver implements ActiveProfilesResolver {
        @Override
        public String[] resolve(Class<?> ignore) {
            return isCI() ? new String[]{"local", "ci"} : new String[]{"local"};
        }

        public static String profile() {
            return isCI() ? "local,ci" : "local";
        }

        public static boolean isCI() {
            return System.getenv("CI") != null;
        }
    }

}
