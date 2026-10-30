#!/bin/sh
# Builds the experiment-only probe into work/probe/probe.jar. Not part of the installable skill.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../../work/probe"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
android_jar=${ANDROID_JAR:-$sdk/platforms/android-37.1/android.jar}
d8=${D8:-$(ls -d "$sdk"/build-tools/*/d8 | sort -V | tail -1)}
java_home=${JAVA_HOME:-$(/usr/libexec/java_home)}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
"$java_home/bin/javac" --release 11 -Xlint:-options -cp "$android_jar" -d "$tmp/classes" \
  $(find "$here/src" -name '*.java')
mkdir -p "$out"
"$d8" --min-api 30 --lib "$android_jar" --output "$tmp" $(find "$tmp/classes" -name '*.class')
(cd "$tmp" && zip -q -X probe.jar classes.dex)
mv "$tmp/probe.jar" "$out/probe.jar"
echo "$out/probe.jar"
