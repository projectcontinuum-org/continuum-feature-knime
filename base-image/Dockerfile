# syntax=docker/dockerfile:1
#
# Base image for the continuum-feature-knime worker.
#
# KNIME Analytics Platform (knime-full) + a dedicated Temurin 21 JRE for the Spring Boot worker.
# The two runtimes are kept separate:
#   - Worker JVM  -> /opt/java/openjdk (Temurin 21), on PATH so Jib's `java` entrypoint resolves to it
#   - KNIME JVM   -> KNIME's bundled JRE, pinned via `-vm` in knime.ini (left untouched)
#
# knime-full is published for linux/amd64 only. On Apple Silicon build with:
#   docker buildx build --platform linux/amd64 -t projectcontinuum/continuum-feature-knime-base:<tag> base-image
#
# NOTE: tune the worker JVM via JDK_JAVA_OPTIONS (read only by the `java` launcher), never
# JAVA_TOOL_OPTIONS — the latter is read by every JVM, including the KNIME one spawned by the worker.

ARG KNIME_IMAGE=knime/knime-full:r-5.5.3-414
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${JRE_IMAGE} AS jre

FROM ${KNIME_IMAGE}

USER root

ENV JAVA_HOME=/opt/java/openjdk \
    KNIME_HOME=/home/knime/knime
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre /opt/java/openjdk ${JAVA_HOME}

# Fail the build if the worker JRE isn't 21, or if KNIME no longer launches with its own bundled JRE.
RUN set -eux; \
    java -version 2>&1 | grep -q 'version "21\.'; \
    grep -A1 '^-vm$' "${KNIME_HOME}/knime.ini" | tail -n1 | grep -q 'org.knime.binary.jre'; \
    test -x "${KNIME_HOME}/knime"

USER knime
WORKDIR /home/knime
