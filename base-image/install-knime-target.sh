#!/usr/bin/env bash
#
# Runs knime-target-installer.jar on KNIME's bundled JRE (the `-vm` entry in knime.ini), so it works
# before / independently of the worker JRE. Extra CAs from the `extra_ca` build secret are trusted by
# both the installer and the p2 director it launches (see with-extra-ca).
#
# Usage: install-knime-target [--dry-run] <file.target>...

set -euo pipefail

knime_home=${KNIME_HOME:?KNIME_HOME is not set}

vm=$(grep -A1 -x -- '-vm' "${knime_home}/knime.ini" | tail -n1)
[[ "${vm}" == /* ]] || vm="${knime_home}/${vm}"

exec with-extra-ca "${vm%/bin}" \
  "${vm}/java" -jar /opt/knime-target-installer/knime-target-installer.jar --knime-home "${knime_home}" "$@"
