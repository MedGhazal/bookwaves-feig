#!/usr/bin/env bash

# Reports what a physical reader supports. Read-only unless --write is passed.
#
#   ./probe.sh <reader-name>            # read-only
#   ./probe.sh <reader-name> --write    # adds a non-persistent write round trip
#   ./probe.sh <reader-name> --write --leave-written   # write, do not restore
#   ./probe.sh <reader-name> --set <param>=<value>     # set one value exactly
#
# Output is meant to be captured and read in full:
#   ./probe.sh gate-in > probe-gate-in.txt 2>&1

set -euo pipefail

mvn -q compile dependency:copy-dependencies

LD_LIBRARY_PATH="$PWD/native/linux.x64" \
CONFIG_FILE_PATH="$PWD/config.yaml" \
java -cp "target/classes:target/dependency/*:libs/*" de.bookwaves.ReaderProbe "$@"
