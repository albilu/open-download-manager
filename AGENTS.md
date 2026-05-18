# Agent Guidelines for Open Download Manager

## Build & Test Commands
- **Build**: `mvn clean install` or `make compile`
- **Run tests**: `mvn test` (runs in Docker container with dependencies)
- **Run single test**: `mvn test -Dtest=ClassName#methodName` (e.g., `mvn test -Dtest=CurlClientTest#testDownloadWithProgress`)
- **Run application**: `make run` (with GUI support via Xvfb)
- **Package**: `make package` (creates .deb, .rpm packages)

## Code Style & Conventions
- **Language**: Java 21 with modular architecture
- **Imports**: Standard Java import order, group by package (java.*, com.*, org.*)
- **Formatting**: 4-space indentation, no tabs; line length ~120 chars
- **Types**: Use explicit types, avoid raw types; leverage Java 21 features (records, pattern matching)
- **Naming**: camelCase for methods/variables, PascalCase for classes, UPPER_SNAKE_CASE for constants
- **Error Handling**: Use proper exception handling; avoid swallowing exceptions; log with context using `java.util.logging.Logger`
- **Logging**: Use structured messages with appropriate levels (debug, info, warn, error); include context tracking
- **Comments**: Javadoc for public APIs; explain "why" not "what" in inline comments

## Testing Guidelines
- Tests run in specialized Docker container with all dependencies (aria2, yt-dlp, GTK)
- **AVOID** mocking critical parts of the core; prefer integration tests over mocks
- Focus on testing core engine implementation, not front-end interactions
- Test files: `**/*Test.java` pattern, use JUnit 5
- **NEVER** use `assertTrue(true)` or other bypass assertions

## Critical Rules
- **DON'T LEAVE METHODS UNIMPLEMENTED** - all methods must have complete implementations
- UI layout is complete in Glade files - **DON'T MODIFY** layout/design, only align widget IDs and signals
- Prioritize performance, memory efficiency, and stability
- Based on aria2, yt-dlp, and httrack - align with these tools' capabilities
- **DON'T** write README, example, or demo files unless explicitly requested
