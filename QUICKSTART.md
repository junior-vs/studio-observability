# Quick Start Guide - OBSERVA4J Extension Development

This guide helps you start developing the OBSERVA4J Quarkus extension.

## Project Structure

```
observa4j/
├── pom.xml                      # Parent POM (aggregator)
├── runtime/                     # Runtime module
│   ├── pom.xml
│   └── src/main/
│       ├── java/io/github/observa4j/runtime/
│       └── resources/
│           ├── META-INF/quarkus-extension.yaml
│           └── application.properties.template
├── deployment/                  # Deployment module
│   ├── pom.xml
│   └── src/main/java/io/github/observa4j/deployment/
│       └── Observa4jProcessor.java
├── integration-tests/           # Integration tests
│   ├── pom.xml
│   └── src/test/
└── concepts/                    # Documentation
```

## Development Workflow

### 1. Build the Extension

```bash
# Clean build
mvn clean install

# Skip tests for faster builds
mvn clean install -DskipTests

# Compile only
mvn clean compile
```

### 2. Run Integration Tests

```bash
cd integration-tests
mvn clean verify
```

### 3. Development Mode

```bash
cd integration-tests
mvn quarkus:dev
```

Access the test application:
- Application: http://localhost:8080
- Dev UI: http://localhost:8080/q/dev
- Health: http://localhost:8080/q/health
- Metrics: http://localhost:8080/q/metrics

## Next Steps

### Implement Core APIs (Runtime Module)

1. **ObservabilityContext** — Request-scoped context carrier
   - File: `runtime/src/main/java/io/github/observa4j/runtime/ObservabilityContext.java`
   - Purpose: Holds trace_id, request_id, user_id, etc.

2. **StructuredLogger** — Main logging API
   - File: `runtime/src/main/java/io/github/observa4j/runtime/StructuredLogger.java`
   - Purpose: 5 Ws structured logging

3. **@Auditable** — Audit annotation
   - File: `runtime/src/main/java/io/github/observa4j/runtime/audit/Auditable.java`
   - Purpose: Mark methods for automatic audit logging

4. **Interceptors** — CDI interceptors
   - AuditInterceptor
   - LoggingInterceptor

### Implement Build Steps (Deployment Module)

1. **ObservabilityContextBuildStep**
   - Auto-register ObservabilityContext as @RequestScoped

2. **InterceptorBuildStep**
   - Register audit and logging interceptors

3. **HealthCheckBuildStep**
   - Discover and register health checks

See `concepts/ARCHITECTURE.md` for detailed design.

## Testing

### Unit Tests

```bash
# Runtime unit tests
cd runtime
mvn test

# Deployment unit tests
cd deployment
mvn test
```

### Integration Tests

```bash
cd integration-tests
mvn verify
```

### Native Build (Optional)

```bash
cd integration-tests
mvn verify -Pnative
```

## IDE Setup

### IntelliJ IDEA

1. File → Open → Select `pom.xml` (root)
2. Wait for Maven import to complete
3. Right-click `integration-tests/pom.xml` → Run Maven → `quarkus:dev`

### VS Code

1. Open folder: `lib-full-logging`
2. Install extensions:
   - Extension Pack for Java
   - Quarkus
3. Terminal: `cd integration-tests && mvn quarkus:dev`

## Common Issues

### Build fails with "dependencies not found"

```bash
# Update Maven dependencies
mvn clean install -U
```

### Extension not detected

Check `runtime/src/main/resources/META-INF/quarkus-extension.yaml` exists.

### Hot reload not working

Make sure you're running from `integration-tests` directory in dev mode.

## Documentation

- [VISION.md](concepts/VISION.md) — Project goals and scope
- [ARCHITECTURE.md](concepts/ARCHITECTURE.md) — System design
- [CODING_STANDARDS.md](concepts/CODING_STANDARDS.md) — Code conventions
- [CONTRIBUTING.md](CONTRIBUTING.md) — Contribution guidelines

## Resources

- [Quarkus Extension Guide](https://quarkus.io/guides/writing-extensions)
- [CDI Reference](https://jakarta.ee/specifications/cdi/)
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [Micrometer Docs](https://micrometer.io/docs)

## Contact

- Issues: https://github.com/observa4j/observa4j/issues
- Discussions: https://github.com/observa4j/observa4j/discussions
