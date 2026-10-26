#!/usr/bin/env bash
# Runs the host JUnit tests: test/**/*Test.java against the pure-Java app
# sources listed in test/sources.txt (no android.jar on the classpath).
# Needs JDK 9 or later (javac --release 8).
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "test/run.sh: $*" >&2; exit 1; }
for tool in javac java curl shasum; do
    command -v "$tool" >/dev/null || die "$tool not found on PATH"
done

# path sha256
JARS=(
    "junit/junit/4.13.2/junit-4.13.2.jar 8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"
    "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar 66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"
    "org/json/json/20240303/json-20240303.jar 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
)
mkdir -p lib
cp=""
for entry in "${JARS[@]}"; do
    path=${entry% *} sum=${entry#* }
    jar="lib/${path##*/}"
    if [ ! -f "$jar" ]; then
        curl -fsSL --retry 3 --max-time 120 "https://repo1.maven.org/maven2/$path" -o "$jar.part"
        if ! echo "$sum  $jar.part" | shasum -a 256 -c --status -; then
            rm -f "$jar.part"
            die "checksum mismatch downloading $path"
        fi
        mv "$jar.part" "$jar"
    fi
    echo "$sum  $jar" | shasum -a 256 -c --status - || die "checksum mismatch for $jar (delete it to re-download)"
    cp="$cp${cp:+:}$jar"
done

# The tests compile app code against json-20240303.jar, which has methods
# Android 4.3's org.json lacks; build.sh would catch most of them, but not
# overloads that resolve differently. Reject the known ones in app sources.
API18_JSON='\.(keySet|toList|toMap|optNumber|optQuery|similar|getNumber|optBigDecimal|optBigInteger|optEnum)\('
if bad=$(grep -rlE 'import org\.json' app/src | xargs grep -nE "$API18_JSON"); then
    echo "$bad" >&2
    die "app sources use org.json methods missing on API 18"
fi

out=build/test
rm -rf "$out"
mkdir -p "$out/classes"

# App sources the tests need: pure Java only (one path per line, # comments).
grep -v '^[[:space:]]*\(#\|$\)' test/sources.txt > "$out/sources.txt"
find test -name '*.java' >> "$out/sources.txt"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$cp" -d "$out/classes" @"$out/sources.txt"

classes=()
while IFS= read -r f; do
    f=${f#test/}
    f=${f%.java}
    classes+=("${f//\//.}")
done < <(find test -name '*Test.java' | sort)
[ ${#classes[@]} -gt 0 ] || die "no *Test.java found"

cp="$out/classes:$cp"
[ -d test/fixtures ] && cp="test/fixtures:$cp"
java -cp "$cp" org.junit.runner.JUnitCore "${classes[@]}"
