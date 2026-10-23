# syntax=docker/dockerfile:1
#
# Base image for the continuum-feature-knime worker.
#
# KNIME Analytics Platform (knime-lts-full) + extra KNIME extensions + a dedicated Temurin 21 JRE for
# the Spring Boot worker. The two runtimes are kept separate:
#   - Worker JVM  -> /opt/java/openjdk (Temurin 21), on PATH so Jib's `java` entrypoint resolves to it
#   - KNIME JVM   -> KNIME's bundled JRE, pinned via `-vm` in knime.ini (left untouched)
#
# knime-lts-full is published for linux/amd64 only. On Apple Silicon build with:
#   docker buildx build --platform linux/amd64 -t projectcontinuum/continuum-feature-knime-base:<tag> base-image
#
# Behind a TLS-intercepting proxy (e.g. Zscaler), pass its root CA as a build secret — it is only
# trusted while building and never written into the image:
#   docker buildx build --secret id=extra_ca,src=/path/to/ca-bundle.pem ...
#
# NOTE: tune the worker JVM via JDK_JAVA_OPTIONS (read only by the `java` launcher), never
# JAVA_TOOL_OPTIONS — the latter is read by every JVM, including the KNIME one spawned by the worker.

ARG KNIME_IMAGE=knime/knime-lts-full:r-5.12.0-114
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${JRE_IMAGE} AS jre

# Compiles knime-target-installer (installs the units of PDE .target files via KNIME's p2 director).
FROM gradle:8.14.3-jdk21-jammy AS target-installer
COPY --chmod=0755 with-extra-ca.sh /usr/local/bin/with-extra-ca
COPY knime-target-installer /src
RUN --mount=type=secret,id=extra_ca,mode=0444 with-extra-ca "${JAVA_HOME}" gradle -p /src build --no-daemon

FROM ${KNIME_IMAGE}

# KNIME minor version of KNIME_IMAGE — selects the matching extension update sites.
ARG KNIME_VERSION=5.12

ENV KNIME_HOME=/home/knime/knime

COPY --from=target-installer /src/build/libs/knime-target-installer.jar /opt/knime-target-installer/
COPY --chmod=0755 with-extra-ca.sh /usr/local/bin/with-extra-ca
COPY --chmod=0755 install-knime-target.sh /usr/local/bin/install-knime-target

# ---- KNIME extensions: one .target file per COPY+RUN so each gets its own cached layer ----
# Repository locations in .target files may use ${env_var:NAME}; build ARGs such as KNIME_VERSION
# are visible as environment variables during RUN.
USER knime

COPY targets/nodepit-batch.target /home/knime/targets/
RUN --mount=type=secret,id=extra_ca,mode=0444 \
    install-knime-target /home/knime/targets/nodepit-batch.target && rm -rf /home/knime/knime-workspace

# ---- Worker JRE ----
USER root

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre /opt/java/openjdk ${JAVA_HOME}

# Fail the build if KNIME_VERSION doesn't match the KNIME install, the worker JRE isn't 21,
# or KNIME no longer launches with its own bundled JRE.
RUN set -eux; \
    ls -d "${KNIME_HOME}/plugins/org.knime.core_${KNIME_VERSION}."* > /dev/null; \
    java -version 2>&1 | grep -q 'version "21\.'; \
    grep -A1 '^-vm$' "${KNIME_HOME}/knime.ini" | tail -n1 | grep -q 'org.knime.binary.jre'; \
    test -x "${KNIME_HOME}/knime"

USER knime
WORKDIR /home/knime
