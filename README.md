# JME Integration Test Support Library

A library that provides base classes and utilities for writing integration tests that start and manage Spring Boot
services via Maven. Part of the [jEAP](https://github.com/jme-admin-ch/jme) ecosystem.

## Overview

When testing jEAP Microservice Examples, there is often the need to start one or more Spring Boot example apps, wait for
them to become healthy, and then run assertions against their APIs. This library handles all the boilerplate: process
lifecycle management, health check polling, Spring profile resolution, and OAuth2 token fetching.

## Modules

| Module                                | Description                                  |
|---------------------------------------|----------------------------------------------|
| `jme-spring-boot-integration-test`    | Core library with base test classes          |
| `jme-spring-boot-integration-test-it` | Example integration test demonstrating usage |

## Getting Started

### Prerequisites

- Java 25+
- Docker & Docker Compose (for container-based services)

### Dependency

Add the library as a test dependency:

```xml

<dependency>
  <groupId>ch.admin.bit.jeap.jme</groupId>
  <artifactId>jme-spring-boot-integration-test</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

## Usage

### Basic Integration Test

Extend `BootServiceSpringIntegrationTestBase` to get both service lifecycle management and a Spring test context:

```java

@Slf4j
public class MyServiceIT extends BootServiceSpringIntegrationTestBase {

  private static final String APP_BASE_URL = "http://localhost:8082/my-app";

  @BeforeAll
  static void startServices() throws Exception {
    startService("my-module-name", APP_BASE_URL);
  }

  @Test
  void testEndpoint() {
    given()
            .baseUri(APP_BASE_URL)
            .when()
            .get("/api/resource")
            .then()
            .statusCode(200);
  }
}
```

### Without Spring Context

If you don't need a Spring application context in your test, extend `BootServiceIntegrationTestBase` directly.

### What the Base Classes Provide

- **Service startup** via `startService(moduleName, baseUrl)` -- launches the module using `mvnw spring-boot:run` and
  waits for its health endpoint to return 200 (up to 3 minutes).
- **Automatic cleanup** -- all started services (including child processes) are destroyed after tests complete.
- **Profile resolution** -- automatically activates the `ci` profile when the `CI` environment variable is set.
- **OAuth2 token fetching** -- `fetchAccessToken(authBaseUrl, clientId, secret)` retrieves an access token via the
  client credentials flow.
- **Awaitility defaults** -- 60-second timeout with 1-second polling interval for `await()` assertions.

### Docker Compose Support

Services under test can use Spring Boot's Docker Compose integration. Provide a `docker-compose.yml` in your project and
configure it in `application.yml`:

```yaml
spring:
  docker:
    compose:
      enabled: true
```

#### Docker Compose Overlay on CI

On CI, a `docker-compose-ci.yml` overlay file is used alongside the base `docker-compose.yml`. The overlay typically:

- **Resets port mappings** (`ports: !reset []`) so that containers do not expose ports to the host. On CI, the test
  runner and the containers communicate over a shared Docker network instead of via `localhost`.
- **Uses an external Docker network** (`networks.default.external: true`) whose name is derived from the
  `COMPOSE_PROJECT_NAME` environment variable. The CI job creates this network so that the test runner container and all
  compose services share the same network.

The overlay is activated via the Spring `ci` profile. In `application-ci.yml`, the compose file list is overridden to
include both files:

```yaml
spring:
  docker:
    compose:
      file:
        - ../docker/docker-compose.yml
        - ../docker/docker-compose-ci.yml
```

Service URLs also change in the CI profile -- instead of `localhost:<mapped-port>`, tests address containers by their
Docker service name (e.g., `http://nginx:80`).

#### CI Profile Activation

The `ci` Spring profile is **automatically activated** when the `CI` environment variable is set. The base class
`BootServiceIntegrationTestBase` passes `-Dspring.profiles.active=ci` to the Maven process that starts the service under
test. This means no manual profile configuration is needed on CI -- the library handles it.

### Example

The `jme-spring-boot-integration-test-it` module contains a complete working example of an integration test using this
library. See
[TestHarnessIT.java](jme-spring-boot-integration-test-it/src/test/java/ch/admin/bit/jeap/jme/it/TestHarnessIT.java)
for how to extend the base class, start a service, and assert against both the application API and a Docker
Compose-managed container.

## Building

```bash
./mvnw verify
```

## Note

This repository is part of the open source distribution of JME.
See [github.com/jme-admin-ch/jme](https://github.com/jme-admin-ch/jme)
for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
