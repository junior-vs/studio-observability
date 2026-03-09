# Contributing to OBSERVA4J

Thank you for your interest in contributing to OBSERVA4J! This document provides guidelines for contributing to this Quarkus extension project.

## Project Structure

OBSERVA4J is a Quarkus extension with a multi-module Maven structure:

- **runtime** — Public API and runtime logic (CDI beans, interceptors, etc.)
- **deployment** — Build-time augmentation (Quarkus `@BuildStep` processors)
- **integration-tests** — Test application to validate the extension

## Before Contributing

1. **Read the documentation** in the `concepts/` directory:
   - [VISION.md](concepts/VISION.md) — Project goals and scope
   - [ARCHITECTURE.md](concepts/ARCHITECTURE.md) — System design
   - [CODING_STANDARDS.md](concepts/CODING_STANDARDS.md) — Naming and style conventions

2. **Check existing issues** to avoid duplicate work

3. **Discuss major changes** by opening an issue first

## Development Setup

### Prerequisites

- Java 21 or later
- Maven 3.9+
- Git

### Build the Project

```bash
# Clone the repository
git clone https://github.com/observa4j/observa4j.git
cd observa4j

# Build all modules
mvn clean install

# Run integration tests
cd integration-tests
mvn clean verify
```

### Run in Development Mode

```bash
cd integration-tests
mvn quarkus:dev
```

## Module Guidelines

### Runtime Module

**Location:** `runtime/src/main/java/io/github/observa4j/runtime/`

**Contains:**
- Public APIs (interfaces, annotations)
- CDI beans and producers
- Interceptors (`@Auditable`, `@Logged`, etc.)
- Runtime configuration classes

**Rules:**
- All public APIs must be documented with Javadoc
- Use CDI `@ApplicationScoped` for stateless beans
- Use `@RequestScoped` for request-bound context
- Follow naming conventions from [CODING_STANDARDS.md](concepts/CODING_STANDARDS.md)

### Deployment Module

**Location:** `deployment/src/main/java/io/github/observa4j/deployment/`

**Contains:**
- `@BuildStep` methods for build-time augmentation
- Index scanning and annotation processing
- Configuration validation

**Rules:**
- All `@BuildStep` methods must have descriptive names
- Document what each build step does
- Avoid runtime reflection — use build-time indexing
- Reference the deployment guide: https://quarkus.io/guides/writing-extensions

### Integration Tests

**Location:** `integration-tests/src/test/java/`

**Contains:**
- `@QuarkusTest` integration tests
- REST endpoint tests
- End-to-end scenarios

**Rules:**
- Test both JVM and native mode when applicable
- Use meaningful test names: `should_emit_trace_id_when_request_arrives()`
- Clean up test resources in `@AfterEach`

## Code Standards

### Java Code

- **Java version:** 21
- **Formatting:** Follow standard Java conventions (4-space indent)
- **Naming:** See [CODING_STANDARDS.md](concepts/CODING_STANDARDS.md)
- **Documentation:** All public APIs require Javadoc

### Commit Messages

Follow conventional commits:

```
feat(tracing): add automatic trace context propagation
fix(logging): correct MDC cleanup in error paths
docs(readme): update installation instructions
test(audit): add integration test for @Auditable
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`

### Pull Request Process

1. **Fork** the repository
2. **Create a branch** from `main`: `git checkout -b feat/my-feature`
3. **Make your changes** following the guidelines above
4. **Test thoroughly**: `mvn clean verify`
5. **Commit** with conventional commit messages
6. **Push** to your fork
7. **Open a Pull Request** with:
   - Clear description of the change
   - Reference to related issues
   - Screenshots/logs if applicable

## Testing Requirements

All contributions must include tests:

- **Unit tests** for utility classes (runtime or deployment module)
- **Integration tests** for APIs (`integration-tests` module)
- **Documentation updates** if public API changes

### Running Tests

```bash
# All tests
mvn clean verify

# Skip integration tests
mvn clean install -DskipITs

# Single test
mvn test -Dtest=MyTest
```

## Documentation

When adding new features:

1. Update relevant concept documents in `concepts/`
2. Add Javadoc to all public classes and methods
3. Update `README.md` if the feature affects usage
4. Consider adding examples to `integration-tests`

## Questions?

- Open an issue for questions
- Check existing documentation in `concepts/`
- Review Quarkus extension development guide: https://quarkus.io/guides/writing-extensions

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
