# Docker Development Setup

Simple Docker setup for Open Download Manager development, testing, and building.

## Quick Start

```bash
# Build the Docker image
make build

# Start development environment
make dev

# Run tests
make test

# Build application
make compile

# Create packages (.deb, .rpm)
make package
```

## Prerequisites

-   Docker
-   X11 server (for GUI applications)

## Usage

### Development

```bash
# Start interactive development container
make dev
# or
./docker-build.sh dev
```

This will:

-   Mount your source code into the container
-   Cache Maven dependencies in ~/.m2
-   Set up X11 forwarding for GUI apps
-   Give you a bash shell to work in

Inside the container you can:

```bash
mvn compile              # Compile code
mvn clean install       # Build packages
mvn test                # Run tests
mvn exec:java           # Run the application
```

### Testing

```bash
# Run all tests
make test
```

Tests run with Xvfb for GUI testing.

### Building

```bash
# Build the application
make compile
```

### Packaging

```bash
# Create .deb and .rpm packages
make package
```

Packages will be created in the `target/` directory.

### Cleanup

```bash
# Remove Docker image and cleanup
make clean
```

## Files

-   `Dockerfile` - Single-stage image with all dependencies
-   `docker-build.sh` - Simple build script
-   `Makefile` - Convenient wrapper commands
-   `.dockerignore` - Optimizes Docker builds

## Dependencies Included

-   java 21 + Maven
-   GTK 3/4 development libraries
-   aria2, httrack, proxychains4, yt-dlp
-   Package building tools (dpkg, rpm)
-   X11 support for GUI testing

The container runs as a non-root `developer` user for security.
