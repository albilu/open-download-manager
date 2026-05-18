FROM ubuntu:22.04

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install all dependencies
RUN apt-get update && apt-get install -y \
    # java 21 and Maven
    openjdk-21-jdk \
    maven \
    # Build tools
    git \
    curl \
    wget \
    # GTK libraries for JNA bindings
    libgtk-3-dev \
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
    # Utilities
    vim \
    tree \
    && rm -rf /var/lib/apt/lists/*

# Set Java environment
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Create non-root user
RUN useradd -m -s /bin/bash developer && \
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
