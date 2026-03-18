package ch.admin.bit.jeap.jme.test;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests involving a spring context, based on
 * {@link BootServiceIntegrationTestBase} for service lifecycle management.
 */
@SpringBootTest(classes = BootServiceSpringIntegrationTestBase.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles(resolver = BootServiceSpringIntegrationTestBase.TestProfileResolver.class)
public abstract class BootServiceSpringIntegrationTestBase extends BootServiceIntegrationTestBase {
    @SpringBootApplication
    public static class TestApp {
    }
}
