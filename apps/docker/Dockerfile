# Build image used by the Makefile when no JDK is installed on the host.
# Android SDK + JDK, plus the tools jpackage needs to build .deb packages.
FROM ghcr.io/cirruslabs/android-sdk:35

RUN apt-get update \
    && apt-get install -y --no-install-recommends fakeroot binutils \
    && rm -rf /var/lib/apt/lists/* \
    && yes | sdkmanager "build-tools;34.0.0" > /dev/null
