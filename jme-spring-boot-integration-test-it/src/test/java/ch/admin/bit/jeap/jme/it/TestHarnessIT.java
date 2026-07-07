package ch.admin.bit.jeap.jme.it;

import ch.admin.bit.jeap.jme.test.BootServiceSpringIntegrationTestBase;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static io.restassured.RestAssured.given;

@Slf4j
@SuppressWarnings("unchecked")
class TestHarnessIT extends BootServiceSpringIntegrationTestBase {

    private static final String APP_BASE_URL = "http://localhost:8082/jme-it";

    private static final int RANDOM_PORT_APP_PORT = reserveFreePorts(1).getFirst();
    private static final String RANDOM_PORT_APP_BASE_URL = "http://localhost:" + RANDOM_PORT_APP_PORT + "/jme-it";

    @BeforeAll
    static void startServices() throws Exception {
        startService("jme-spring-boot-integration-test-it", APP_BASE_URL);
        // Start a second instance of the app on a reserved free port with a configuration property override
        startService("jme-spring-boot-integration-test-it", RANDOM_PORT_APP_BASE_URL, Map.of(
                "server.port", String.valueOf(RANDOM_PORT_APP_PORT),
                "test.greeting", "overridden"));
    }

    @Test
    void runAppTest(@Value("${test.webserver.url}") String testWebserverUrl) {
        given()
                .baseUri(APP_BASE_URL)
                .when()
                .get("/test")
                .then()
                .statusCode(HttpStatus.OK.value());

        // Verify access to the webserver in the container started using docker-compose works
        given()
                .baseUri(testWebserverUrl)
                .when()
                .get("/")
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void runAppOnReservedFreePortWithPropertyOverrides() {
        // The app instance answering on the reserved free port proves the server.port override was applied
        given()
                .baseUri(RANDOM_PORT_APP_BASE_URL)
                .when()
                .get("/test")
                .then()
                .statusCode(HttpStatus.OK.value());

        // The overridden property value proves generic configuration property overrides are applied
        given()
                .baseUri(RANDOM_PORT_APP_BASE_URL)
                .when()
                .get("/greeting")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body(Matchers.equalTo("overridden"));
    }
}
