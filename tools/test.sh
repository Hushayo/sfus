#!/bin/bash
# Compile and run the offline test suite.
#
#   ./tools/test.sh            # compile, then run every suite
#   ./tools/test.sh --no-build # run against the existing out/ directory
#
# Exits non-zero when a test fails, so it can gate a build.
set -e
cd "$(dirname "$0")/.."

if [ "$1" != "--no-build" ]; then
    mkdir -p out
    javac -d out $(find src -name "*.java")
fi

# src is on the classpath because the registry tables are resources, not
# compiled classes: without it every table loads empty and the tests fail for
# the wrong reason.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac
java -cp "out${SEP}src" mcbot.dev.TestAll
