package ch.admin.bit.jeap.jme.test;

import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.ActiveProfilesResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final Path PROJECT_ROOT = getProjectRoot();
    private static final String MVN = PROJECT_ROOT.resolve("mvnw").toString();
    private static final String DEFAULT = "default";

    private static @NonNull Path getProjectRoot() {
        Path currentPath = Path.of("").toAbsolutePath();
        if (currentPath.resolve("mvnw").toFile().exists()) {
            return currentPath;
        } else {
            return currentPath.getParent();
        }
    }

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
        startService(moduleName, baseUrl, Map.of());
    }

    protected static void startService(String baseUrl) throws IOException {
        startService(null, baseUrl);
    }

    /**
     * Starts a service with additional configuration property overrides. The properties are passed to the forked
     * service JVM as system properties and therefore take precedence over the properties in the service's
     * configuration files. Use this e.g. to start services on reserved free ports (see {@link #reserveFreePorts(int)})
     * and to point the services at each other's dynamically assigned URLs:
     * {@code startService("my-module", baseUrl, Map.of("server.port", port, "peerService.url", peerUrl))}.
     *
     * @param moduleName              name of the maven module to start, or {@code null} for a project without modules
     * @param baseUrl                 base URL of the service, used to poll the readiness health endpoint
     * @param configurationProperties configuration properties to override in the started service
     */
    protected static void startService(String moduleName, String baseUrl, Map<String, String> configurationProperties)
            throws IOException {
        log.info("Starting {}...", moduleName != null ? moduleName : DEFAULT);
        Process process = startMavenService(moduleName, TestProfileResolver.profile(), configurationProperties);
        startedServices.addFirst(process);
        var healthUrl = baseUrl + "/actuator/health/readiness";
        waitForService(healthUrl, SERVICE_STARTUP_TIMEOUT);
        log.info("{} is ready.", moduleName != null ? moduleName : DEFAULT);
    }

    /**
     * Reserves free TCP ports for starting services on ports that are not in use, avoiding conflicts with other
     * services running on the machine. The ports are only reserved momentarily while this method runs, so there is a
     * small chance of another process claiming a returned port before the service starts.
     *
     * @param count number of distinct free ports to reserve
     * @return the reserved free ports
     */
    protected static List<Integer> reserveFreePorts(int count) {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                sockets.add(new ServerSocket(0));
            }
            return sockets.stream().map(ServerSocket::getLocalPort).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reserve free ports", e);
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.warn("Failed to close port reservation socket", e);
                }
            }
        }
    }

    private static Process startMavenService(String moduleName, String springProfile,
                                             Map<String, String> configurationProperties) throws IOException {
        List<String> cmds = new ArrayList<>();
        cmds.add(MVN);
        if (TestProfileResolver.isCI()) {
            log.info("Running on CI, using workspace-local maven settings file");
            cmds.add("-s");
            cmds.add("settings.xml");
        }

        cmds.addAll(List.of(
                "spring-boot:run",
                "-Dspring-boot.run.profiles=" + springProfile));

        if (!configurationProperties.isEmpty()) {
            String jvmArguments = configurationProperties.entrySet().stream()
                    .map(entry -> "\"-D" + entry.getKey() + "=" + entry.getValue() + "\"")
                    .collect(Collectors.joining(" "));
            cmds.add("-Dspring-boot.run.jvmArguments=" + jvmArguments);
        }

        if (moduleName != null) {
            cmds.addAll(List.of("--projects", moduleName));
        }

        ProcessBuilder pb = new ProcessBuilder(cmds);
        pb.directory(PROJECT_ROOT.toFile());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Thread outputThread = createStdIoCopyThread(moduleName != null ? moduleName : DEFAULT, process);
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
            log.info("Stopping child process {}", processHandle.pid());
            processHandle.destroyForcibly();
        });
        log.info("Stopping process {}", process.pid());
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
