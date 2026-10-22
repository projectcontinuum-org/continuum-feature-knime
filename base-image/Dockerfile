# syntax=docker/dockerfile:1
#
# Base image for the continuum-feature-knime worker.
#
# KNIME Analytics Platform (knime-full) + extra KNIME extensions + a dedicated Temurin 21 JRE for
# the Spring Boot worker. The two runtimes are kept separate:
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

# KNIME minor version of KNIME_IMAGE — selects the matching extension update sites.
ARG KNIME_VERSION=5.5

ENV KNIME_HOME=/home/knime/knime

COPY --chmod=0755 install-knime-extensions.sh /usr/local/bin/install-knime-extensions

# ---- KNIME extensions: one RUN per extension so each gets its own cached layer ----
USER knime

# NodePit Batch — provides com.nodepit.batch.application.NodePitBatchExecutor used by KNIMECliService
RUN install-knime-extensions https://download.nodepit.com/${KNIME_VERSION} com.nodepit.batch.feature.feature.group

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
