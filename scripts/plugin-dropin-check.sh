#!/usr/bin/env bash
#
# Checks the Open Mason drop-in plugin jar (openmason-plugin-stonebreak-*-dropin.jar, built by
# `mvn package`): what users put in Open Mason's plugin folder. Run after a package build:
#
#   scripts/plugin-dropin-check.sh [extra mvn args...]      (CI: -B -ntp -s .mvn/settings.xml)
#
#  1. Contents: the plugin's service entry; none of Open Mason's own classes or libraries (it supplies
#     them, and a second copy could shadow nothing but would only grow the jar); no module-info; no
#     game music or natives.
#  2. Bundled dependencies: the plugin POM's shade <includes> must be exactly the plugin's runtime
#     dependencies that Open Mason does not have (plugin tree minus openmason-tool's tree). A new game
#     dependency missing from the list would be a NoClassDefFoundError inside Open Mason.
#  3. Loading: Open Mason's own loader (PluginManager.discoverJars from the openmason-tool shaded jar
#     at the POM's openmason.version) finds the plugin and registers it ACTIVE, headless.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
MVN=(mvn -q "$@")
WORK="$ROOT/openmason-plugin-stonebreak/target/dropin-check"
rm -rf "$WORK"
mkdir -p "$WORK/plugins"

fail=0
err() { echo "::error::$*"; fail=1; }

shopt -s nullglob
jars=(openmason-plugin-stonebreak/target/openmason-plugin-stonebreak-*-dropin.jar)
[[ ${#jars[@]} -eq 1 ]] || { echo "::error::expected one drop-in jar, found ${#jars[@]} (run mvn package first)"; exit 1; }
jar="${jars[0]}"
echo "drop-in: $jar ($(( $(wc -c <"$jar") / 1048576 )) MiB)"

# 1. Contents
jar tf "$jar" > "$WORK/entries.txt"
(cd "$WORK" && jar xf "$ROOT/$jar" META-INF/services/com.openmason.api.OpenMasonPlugin) || true
grep -qx "com.stonebreak.openmason.StonebreakPlugin" "$WORK/META-INF/services/com.openmason.api.OpenMasonPlugin" 2>/dev/null \
  || err "no META-INF/services entry for StonebreakPlugin"
for prefix in com/openmason/main/ com/openmason/api/ com/openmason/engine/format/ com/openmason/engine/ui/ \
              org/lwjgl/ io/github/humbleui/ imgui/ io/netty/ org/slf4j/ ch/qos/logback/ \
              com/fasterxml/jackson/databind/ songs/ natives/; do
  if grep -q "^$prefix" "$WORK/entries.txt"; then err "drop-in carries $prefix (Open Mason provides it / not needed)"; fi
done
grep -q "module-info.class$" "$WORK/entries.txt" && err "drop-in carries a module-info.class"
grep -q "^com/openmason/engine/voxel/" "$WORK/entries.txt" || err "drop-in lacks engine-voxel (the game needs it)"

# 2. Bundled dependencies vs the dependency trees
cat > "$WORK/probe-pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.stonebreak</groupId>
    <artifactId>stonebreak-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <relativePath>../../../pom.xml</relativePath>
  </parent>
  <artifactId>openmason-tool-probe</artifactId>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>com.openmason</groupId>
      <artifactId>openmason-tool</artifactId>
    </dependency>
  </dependencies>
</project>
EOF
"${MVN[@]}" -f "$WORK/probe-pom.xml" dependency:tree -Dscope=runtime -DoutputFile="$WORK/tool-tree.txt"
"${MVN[@]}" -pl openmason-plugin-stonebreak -am dependency:tree -Dscope=runtime -DoutputFile="$WORK/plugin-tree.txt"
# "group:artifact:type[:classifier]:version:scope" -> "group:artifact"
keys() { sed -E 's/^[|+\\ -]*//' "$1" | awk -F: 'NF>=5 {print $1":"$2}' | sort -u; }
keys "$WORK/tool-tree.txt" > "$WORK/tool.txt"
keys "$WORK/plugin-tree.txt" | grep -v '^com\.stonebreak:' > "$WORK/plugin.txt"
comm -23 "$WORK/plugin.txt" "$WORK/tool.txt" > "$WORK/needed.txt"
sed -n '/<artifactSet>/,/<\/artifactSet>/p' openmason-plugin-stonebreak/pom.xml \
  | sed -n 's:.*<include>\(.*\)</include>.*:\1:p' | grep -v '^com\.stonebreak:' | sort -u > "$WORK/bundled.txt"
if ! diff -u "$WORK/needed.txt" "$WORK/bundled.txt" > "$WORK/deps.diff"; then
  err "the drop-in's bundled dependencies drifted from the game's (- needed but not bundled, + bundled but Open Mason has it); fix <artifactSet> in openmason-plugin-stonebreak/pom.xml"
  cat "$WORK/deps.diff"
else
  echo "bundled dependencies: $(tr '\n' ' ' < "$WORK/bundled.txt")"
fi

# 3. Load it the way Open Mason does
# -N: these are root-POM calls; recursing would repeat them in every reactor module.
version=$("${MVN[@]}" -N help:evaluate -Dexpression=openmason.version -DforceStdout)
# -U: always ask GitHub Packages for the newest snapshot, whatever an earlier step cached.
"${MVN[@]}" -N -U dependency:copy -Dartifact="com.openmason:openmason-tool:$version:jar:shaded" \
  -DoutputDirectory="$WORK/tool"
# copy keeps a remote snapshot's timestamped version (useBaseVersion=false by default), e.g.
# openmason-tool-0.1.0-20261101.095012-15-shaded.jar: find the file instead of assuming its name.
tools=("$WORK"/tool/openmason-tool-*-shaded.jar)
if [[ ${#tools[@]} -ne 1 ]]; then
  ls -la "$WORK/tool" || true
  echo "::error::expected one openmason-tool shaded jar from dependency:copy, found ${#tools[@]}"
  exit 1
fi
tool="${tools[0]}"
resolved=$(basename "$tool"); resolved=${resolved#openmason-tool-}; resolved=${resolved%-shaded.jar}
echo "Open Mason $version resolved to build $resolved ($(basename "$tool"))"
if ! jar tf "$tool" | grep -qx 'com/openmason/main/plugins/PluginManager.class'; then
  echo "::error::$(basename "$tool") has no com/openmason/main/plugins/PluginManager (not the tool's runnable jar?)"
  exit 1
fi
# Drop-in loading (Sonar-Arts/OpenMason cb4ef588): an older snapshot cannot load the plugin at all.
if ! javap -cp "$tool" com.openmason.main.plugins.PluginManager | grep -q ' discoverJars('; then
  echo "::error::Open Mason build $resolved predates drop-in plugin loading (no PluginManager.discoverJars): publish a newer OpenMason snapshot"
  exit 1
fi
cp "$jar" "$WORK/plugins/"
cat > "$WORK/DropInProbe.java" <<'EOF'
import com.openmason.main.plugins.EditorRegistries;
import com.openmason.main.plugins.PluginManager;
import java.nio.file.Path;
import java.util.List;

public class DropInProbe {
    public static void main(String[] args) {
        PluginManager pm = new PluginManager(new EditorRegistries());
        var found = pm.discoverJars(Path.of(args[0]));
        pm.registerAll(List.of(), found);
        pm.statuses().forEach(s -> System.out.println("plugin " + s.info().id() + ": " + s.state()
                + (s.detail() == null ? "" : " (" + s.detail() + ")")));
        System.exit(pm.isActive(args[1]) ? 0 : 1);
    }
}
EOF
rc=0
out=$(java --enable-native-access=ALL-UNNAMED -cp "$tool" \
        "$WORK/DropInProbe.java" "$WORK/plugins" stonebreak 2>&1) || rc=$?
grep -E '^plugin |ERROR|Exception' <<<"$out" || true
[[ $rc -eq 0 ]] || { err "Open Mason $version did not load and register the drop-in plugin"; tail -n 40 <<<"$out"; }

exit $fail
