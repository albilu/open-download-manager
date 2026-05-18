# Technology Stack

## Build System

-   **Maven**: Multi-module project with parent POM
-   **Java 21**: Required minimum version
-   **Docker**: Development environment with GUI support

## Core Technologies

-   **JNA (Java Native Access)**: For GTK bindings and native library integration
-   **Jackson**: JSON processing for configuration and API communication
-   **Java-WebSocket**: WebSocket client for aria2 RPC communication
-   **SLF4J**: Logging framework

## External Tools Integration

-   **aria2**: Primary download engine with RPC interface
-   **yt-dlp**: YouTube and video platform downloads
-   **httrack**: Website scraping and mirroring
-   **curl**: HTTP downloads and proxy fallback
-   **proxychains**: SOCKS proxy support
-   **Tor**: Privacy and anonymization

## UI Framework

-   **GTK 3/4**: Native Linux desktop integration via JNA
-   **Glade**: XML-based UI definition files
-   **Custom JGTK library**: Simplified GTK bindings for ODM

## Testing

-   **JUnit 5**: Unit and integration testing
-   **Mockito**: Mocking framework
-   **Awaitility**: Asynchronous testing
-   **MockWebServer**: HTTP testing
-   **JMH**: Performance benchmarking

## Common Commands

### Development

```bash
# Build Docker image and start development
make dev

# Build application
make compile
mvn clean compile package -DskipTests

# Run tests
make test
mvn test

# Run application with GUI
make run

# Debug mode (port 5005)
make debug
```

### Packaging

```bash
# Create all distribution packages
make package
mvn install

# Individual module builds
mvn clean package -pl core
mvn clean package -pl jgtk
mvn clean package -pl odm-gtk
```

### Docker Development

```bash
# Build image
./docker-build.sh build

# Interactive development
./docker-build.sh dev

# Run with X11 forwarding
./docker-build.sh run
```

## Module Dependencies

-   `odm-gtk` depends on `jgtk` and `odm-core`
-   `jgtk` is standalone GTK bindings
-   `odm-core` contains all business logic and tool integrations
