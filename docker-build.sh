#!/bin/bash
# Simple Docker build script for Open Download Manager
set -e

PROJECT_NAME="open-download-manager"
IMAGE_NAME="odm-dev"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[ODM]${NC} $1"
}

# Ensure the shared Maven cache directory is writable by the container's
# developer user (uid 1000). Docker resolves bind-mount sources on the host,
# so on fresh CI runners (and under nested Docker/CI) the source is
# auto-created root-owned, making Maven fail with "Could not create local
# repository at /home/developer/.m2/repository". Normalize permissions through
# Docker itself so the path the daemon actually mounts is writable.
prepare_m2() {
    mkdir -p "$HOME/.m2"
    docker run --rm -u 0 -v "$HOME/.m2:/m2" "$IMAGE_NAME" chmod 0777 /m2 2>/dev/null || true
    chmod 0777 "$HOME/.m2" 2>/dev/null || true
}

# X11 authentication forwarder. Wayland/Xwayland sessions gate the display
# behind an Xauthority token (e.g. /run/user/*/.mutter-Xwaylandauth.*). Docker
# containers must receive that token or the X server rejects the connection
# with "Authorization required, but no authorization protocol specified".
# The token is copied to a stable host path before each container launch.
xauth_args() {
    if [ -z "$DISPLAY" ] || [ -z "$XAUTHORITY" ] || [ ! -f "$XAUTHORITY" ]; then
        return
    fi
    local host_auth="$HOME/.odm-xauthority"
    if ! cp "$XAUTHORITY" "$host_auth" 2>/dev/null; then
        return
    fi
    echo " -e XAUTHORITY=/tmp/odm-xauthority -v $host_auth:/tmp/odm-xauthority:rw"
}

# Run application
run() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application with GUI..."
    docker run --rm \
        -v "$(pwd):/app" \
        -v "$(pwd)/docker-data:/app/data" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --ipc=host \
        $IMAGE_NAME \
        bash -c 'mvn -q -pl odm-gtk4 -am package -DskipTests=true && mvn -q -pl odm-gtk4 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -Djava.util.logging.level=FINE -Djava.util.logging.ConsoleHandler.level=FINE -cp "odm-gtk4/target/classes:$(cat /tmp/cp.txt)" org.odm.gtk4.OdmApplication' 
}

# Run application in debug mode
debug() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application in debug mode (port 5005) with GUI..."
    docker run --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        -p 5005:5005 \
        --ipc=host \
        $IMAGE_NAME \
        bash -c 'mvn -q -pl odm-gtk4 -am package -DskipTests=true && mvn -q -pl odm-gtk4 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:5005 -Djava.util.logging.level=FINE -Djava.util.logging.ConsoleHandler.level=FINE -cp "odm-gtk4/target/classes:$(cat /tmp/cp.txt)" org.odm.gtk4.OdmApplication' 
}

# Build the Docker image
build() {
    log "Building Docker image..."
    docker build -t $IMAGE_NAME .
}

# Start development container
dev() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Starting development container..."
    docker run -it --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --name odm-dev \
        $IMAGE_NAME
}

# Run tests
test() {
    prepare_m2
    log "Running tests..."
    docker run --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e PROXYCHAINS_AVAILABLE=true \
        -e ENABLE_NETWORK_TESTS=true \
        $IMAGE_NAME \
        bash -c "Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /dev/null 2>&1 & sleep 2 && mvn test"
}

# Build application
compile() {
    prepare_m2
    log "Building application..."
    docker run --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        mvn clean compile package -DskipTests=true
}



# Create packages
package() {
    prepare_m2
    local version="${1:-0.1.0}"
    log "Creating packages (version ${version})..."
    docker run --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        bash -c "cd /app && packaging/build-packages.sh ${version}"
}

# Clean up
clean() {
    log "Cleaning up..."
    docker rmi $IMAGE_NAME 2>/dev/null || true
    docker system prune -f
}

# Show help
help() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  build     Build Docker image"
    echo "  dev       Start development container"
    echo "  test      Run tests"
    echo "  compile   Build application"
    echo "  run       Run application with GUI support"
    echo "  debug     Run application in debug mode (port 5005)"
    echo "  package   Create distribution packages"
    echo "  clean     Clean up Docker resources"
    echo "  help      Show this help"
}

# Main
case "${1:-help}" in
    build)   build ;;
    dev)     build && dev ;;
    test)    build && test ;;
    compile) build && compile ;;
    run)     build && run ;;
    debug)   build && debug ;;
    package) shift; build && package "$@" ;;
    clean)   clean ;;
    help)    help ;;
    *)       help ;;
esac
