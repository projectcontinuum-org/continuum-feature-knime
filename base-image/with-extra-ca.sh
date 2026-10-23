#!/usr/bin/env bash
#
# Runs a command with extra CA certificates trusted by every JVM it starts — for build environments
# behind a TLS-intercepting proxy (e.g. Zscaler).
#
# Usage: with-extra-ca <java-home> <command> [args...]
#
# If the BuildKit secret `extra_ca` (PEM bundle) is mounted at /run/secrets/extra_ca, builds a
# temporary truststore from <java-home>'s cacerts plus those certificates and points JVMs at it via
# JAVA_TOOL_OPTIONS. The truststore is deleted afterwards, so nothing ends up in the image layer.
# Without the secret, the command runs unchanged.
#
#   docker buildx build --secret id=extra_ca,src=/path/to/ca-bundle.pem ...

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $(basename "$0") <java-home> <command> [args...]" >&2
  exit 2
fi

java_home=$1
shift
extra_ca=/run/secrets/extra_ca

if [[ ! -s "${extra_ca}" ]]; then
  exec "$@"
fi

work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

truststore="${work}/cacerts"
cp "${java_home}/lib/security/cacerts" "${truststore}"

# keytool imports one certificate per call — split the bundle
awk -v dir="${work}" '/-----BEGIN CERTIFICATE-----/ { n++ } n { print > (dir "/extra-" n ".pem") }' "${extra_ca}"
count=0
for cert in "${work}"/extra-*.pem; do
  [[ -e "${cert}" ]] || continue
  "${java_home}/bin/keytool" -importcert -noprompt -keystore "${truststore}" -storepass changeit \
    -alias "extra-ca-$(basename "${cert}" .pem)" -file "${cert}" > /dev/null
  count=$((count + 1))
done
echo "with-extra-ca: trusting ${count} extra CA certificate(s)" >&2

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-Djavax.net.ssl.trustStore=${truststore} -Djavax.net.ssl.trustStorePassword=changeit"
"$@"
