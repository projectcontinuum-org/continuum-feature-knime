#!/usr/bin/env bash
#
# Installs KNIME extensions with KNIME's own p2 director (runs on KNIME's bundled JRE).
#
# Usage: install-knime-extensions <update-site>[,<update-site>...] <feature-id>[,<feature-id>...]
#
# Call once per extension (one Dockerfile RUN each) so every extension lands in its own image layer.
# Fails if any requested feature is not an installed root afterwards.

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $(basename "$0") <update-site>[,<update-site>...] <feature-id>[,<feature-id>...]" >&2
  exit 2
fi

sites=$1
features=$2
knime_home=${KNIME_HOME:?KNIME_HOME is not set}

director=("${knime_home}/knime" -application org.eclipse.equinox.p2.director -consolelog -nosplash)

echo "====>>> Installing KNIME features [${features}] from [${sites}]"
"${director[@]}" -r "${sites}" -i "${features}"

missing=$(tr ',' '\n' <<< "${features}" | grep -vxF -f <("${director[@]}" -listInstalledRoots | cut -d/ -f1) || true)
if [[ -n "${missing}" ]]; then
  echo "KNIME features not installed: ${missing//$'\n'/, }" >&2
  exit 1
fi

# Drop the workspace the director run leaves behind so it doesn't bloat the layer.
rm -rf "${HOME}/knime-workspace"
