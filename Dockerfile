FROM eclipse-temurin:25-jdk

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install all dependencies
RUN apt-get update && apt-get install -y \
    # Maven (JDK 25 comes from the base image)
    maven \
    # Build tools
    git \
    curl \
    wget \
    # GTK libraries for java-gi (GTK4)
    libgtk-4-dev \
    libglib2.0-dev \
    pkg-config \
    # External dependencies (matching debian/control versions)
    aria2 \
    httrack \
    proxychains4 \
    tor \
    yt-dlp \
    # X11 for GUI testing
    xvfb \
    x11-utils \
    dbus-x11 \
    # Package building tools
    dpkg-dev \
    fakeroot \
    rpm \
    zstd \
    # Utilities
    vim \
    tree \
    && rm -rf /var/lib/apt/lists/*

# JAVA_HOME is already set by the temurin base image

# Create non-root user. Newer base images already have a default user holding
# uid 1000 (e.g. "ubuntu"); reclaim uid 1000 so file ownership on mounted
# volumes matches the typical host user.
RUN (userdel -r ubuntu 2>/dev/null || true) && \
    useradd -m -s /bin/bash -u 1000 developer && \
    usermod -aG sudo developer && \
    echo "developer ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Set up work directory
WORKDIR /app
RUN chown developer:developer /app

# Switch to developer user
USER developer

# Set up display for GUI testing
ENV DISPLAY=:99

# Default command
CMD ["/bin/bash"]
