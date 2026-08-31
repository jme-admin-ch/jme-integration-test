# JME Integration Test Support Library

A library that provides base classes and utilities for writing integration tests that start and manage Spring Boot
services via Maven. Part of the [jEAP](https://github.com/jme-admin-ch/jme) ecosystem.

## Overview

When testing jEAP Microservice Examples, there is often the need to start one or more Spring Boot example apps, wait for
them to become healthy, and then run assertions against their APIs. This library handles all the boilerplate: process
lifecycle management, health check polling, Spring profile resolution, and OAuth2 token fetching.

## Test Strategy

The OSS variants of the JME Examples are not deployed, so they are tested against their
`local` profile configuration in the CI pipeline. This also gives high confidence in the
quality of the examples when automated dependency updates are applied.

Examples come in different flavours, each with its own test strategy:

| Example structure                                        | Test strategy                                                                                                                                                                                                                          |
|----------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Single module, with a Docker Compose infrastructure file | Use `jme-integration-test` with a Spring context and Docker Compose in the test. Add the integration test under `src/test/java`.                                                                                                          |
| Single module, without Docker Compose                    | Use `jme-integration-test` without a Spring context in the test. Add the integration test under `src/test/java`.                                                                                                                          |
| Multi-module, with a Docker Compose infrastructure file  | Use `jme-integration-test` with a Spring context and Docker Compose in the test. Create a separate test module and enable Spring Docker Compose in it. The test support then runs the Spring applications from the other modules via `mvn spring-boot:run`. |
| Multi-module, without Docker Compose                     | Use `jme-integration-test` with a Spring context in the test. Create a separate test module. The test support then runs the Spring applications from the other modules via `mvn spring-boot:run`.                                          |

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
- **Configuration property overrides** via `startService(moduleName, baseUrl, configurationProperties)` -- passes the
  given properties to the started service as system properties, taking precedence over the service's configuration
  files.
- **Free port reservation** via `reserveFreePorts(count)` -- reserves distinct free TCP ports, e.g. to start services
  on random ports instead of the fixed ports configured in their configuration files.
- **Automatic cleanup** -- all started services (including child processes) are destroyed after tests complete.
- **Profile resolution** -- automatically activates the `ci` profile when the `CI` environment variable is set.
- **OAuth2 token fetching** -- `fetchAccessToken(authBaseUrl, clientId, secret)` retrieves an access token via the
  client credentials flow.
- **Awaitility defaults** -- 60-second timeout with 1-second polling interval for `await()` assertions.

### Starting Services on Random Ports

Services configured with fixed ports can be started on reserved free ports to avoid conflicts with other processes,
e.g. with instances of the services started manually. Reserve a free port per service, then override each service's
`server.port` and the URLs the services use to call each other:

```java
private static final List<Integer> PORTS = reserveFreePorts(2);
private static final String APP_BASE_URL = "http://localhost:" + PORTS.get(0) + "/my-app";
private static final String PEER_BASE_URL = "http://localhost:" + PORTS.get(1) + "/my-peer";

@BeforeAll
static void startServices() throws Exception {
    startService("my-peer-module", PEER_BASE_URL, Map.of(
            "server.port", String.valueOf(PORTS.get(1))));
    startService("my-app-module", APP_BASE_URL, Map.of(
            "server.port", String.valueOf(PORTS.get(0)),
            "peerService.url", PEER_BASE_URL));
}
```

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
