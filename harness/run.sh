#!/usr/bin/env bash
# Compiles the platform-independent core together with Harness.kt and runs it. Compiling them in
# one invocation puts the harness in the same module, so it can see `internal` declarations —
# the same trick the unit tests use.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/featureflow-harness"
SRC="featureflow/src/main/kotlin/io/featureflow/android"
mkdir -p "$OUT"

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "kotlinc not found. Install it with:  sdk install kotlin" >&2
  exit 1
fi

JSON_JAR="$OUT/json.jar"
if [ ! -f "$JSON_JAR" ]; then
  echo "Fetching org.json…" >&2
  curl -sSLf -o "$JSON_JAR" \
    "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
fi

echo "Compiling…" >&2
kotlinc -nowarn -classpath "$JSON_JAR" -d "$OUT/classes" \
  "$SRC/JsonValue.kt" "$SRC/FeatureflowUser.kt" "$SRC/Models.kt" "$SRC/Conditions.kt" \
  "$SRC/RuleEvaluator.kt" "$SRC/Evaluation.kt" "$SRC/FeatureflowConfig.kt" \
  "$SRC/RestClient.kt" harness/Harness.kt

KOTLIN_STDLIB="$(dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar"
exec java -cp "$JSON_JAR:$OUT/classes:$KOTLIN_STDLIB" io.featureflow.android.HarnessKt "$@"
