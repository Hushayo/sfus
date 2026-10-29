#!/bin/bash
# Build sfus.jar: compile, copy the generated data resources, package.
#
# The registry tables under src/mcbot/data are classpath resources, not
# compiled classes, so javac alone produces a jar that loads zero blocks.
set -e
cd "$(dirname "$0")"

OUT=out
JAR=sfus.jar

rm -rf "$OUT"
mkdir -p "$OUT"

javac -d "$OUT" $(find src -name "*.java")
cp -r src/mcbot/data "$OUT/mcbot/data"

jar --create --file "$JAR" --main-class mcbot.Main -C "$OUT" .
echo "built $JAR"
