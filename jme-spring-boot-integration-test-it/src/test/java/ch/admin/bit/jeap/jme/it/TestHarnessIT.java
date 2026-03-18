package ch.admin.bit.jeap.jme.it;

import ch.admin.bit.jeap.jme.test.BootServiceSpringIntegrationTestBase;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;

@Slf4j
@SuppressWarnings("unchecked")
public class TestHarnessIT extends BootServiceSpringIntegrationTestBase {

    private static final String APP_BASE_URL = "http://localhost:8082/jme-it";

    @BeforeAll
    static void startServices() throws Exception {
        startService("jme-spring-boot-integration-test-it", APP_BASE_URL);
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
}
