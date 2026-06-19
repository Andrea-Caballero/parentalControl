# Verification — primary path

## Outcome: PASS

AGP **8.13.2** ships R8 **8.13.19**, whose bundled `kotlin-metadata-jvm` supports
metadata version **v2.3.0** (the version emitted by Kotlin 2.3.0 used in this
project). The 1171 `malformed kotlin.Metadata` warnings should disappear after
the bump.

## Note on the verification mechanism

The proposal/spec/tasks all specified the verification as
`curl -s https://repo1.maven.org/maven2/com/android/tools/r8/<v>/r8-<v>.pom | grep -A2 kotlin-metadata-jvm`.
That mechanism does **not** work and was not used:

1. The `com.android.tools:r8` artifact is **not published to Maven Central** —
   it lives on **Google Maven** (`https://dl.google.com/dl/android/maven2/`).
   Maven Central only hosts `com.android.tools:build:*`, `lint:*`, `analytics-library:*`, etc.
2. The R8 POM has **zero dependencies** — `kotlin-metadata-jvm` is shaded into
   the R8 jar (relocated under `com.android.tools.r8.kotlin.*`), so a grep on
   the POM would never produce a hit.
3. The R8 version number is **decoupled** from the AGP version number:
   AGP 8.9.2 ships R8 8.9.35, and AGP 8.13.2 ships R8 8.13.19. The proposal's
   literal URL `r8/8.13.2/r8-8.13.2.pom` does not exist.

The verification below uses the working mechanism: download the AGP plugin and
the R8 jar from Google Maven, and inspect the bytecode constants that encode
the supported Kotlin metadata version.

## Step 1 — AGP 8.13.2 ships which R8?

```bash
curl -s -o gradle-8.13.2.jar \
  https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2.jar

curl -s -o builder-8.13.2.jar \
  https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.13.2/builder-8.13.2.jar

javap -p -c -constants -classpath builder-8.13.2.jar \
  com.android.builder.dexing.R8Version
```

Constant pool entry observed:

```java
public static final java.lang.String VERSION_AGP_WAS_SHIPPED_WITH = "8.13.19";
```

→ **AGP 8.13.2 ships R8 8.13.19.**

## Step 2 — What is the max supported Kotlin metadata version in R8 8.13.19?

```bash
curl -s -o r8-8.13.19.jar \
  https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.19/r8-8.13.19.jar

# Find the metadata-version-checking class
strings r8-8.13.19.jar | grep "while maximum supported version is"
# → com/android/tools/r8/internal/l02.class

# Find the version constant class
javap -c -classpath r8-8.13.19.jar com.android.tools.r8.internal.gc2
```

The `gc2` static initializer (paraphrased):

```java
g = gc2(new int[]{2, 2, 0}, false);   // stable max = 2.2.0
if (g.major == 1 && g.minor == 9) {
    h = gc2(new int[]{2, 0, 0}, false);    // legacy special case
} else {
    h = gc2(new int[]{g.major, g.minor + 1, 0}, false);  // experimental max
}
// → h = gc2({2, 2+1, 0}, false) = gc2({2, 3, 0}, false)
```

→ `gc2.h` = **[2, 3, 0] = Kotlin metadata v2.3.0** — the max supported version.

The selection in the validation path (`l02` class) is `isAllowedToWrite=false`
→ uses `h` = v2.3.0, which is exactly what the R8 runtime needs for reading
Kotlin 2.3.0 classes.

## Step 3 — Cross-check: root cause matches AGP 8.9.2's R8

To confirm the root cause chain (AGP 8.9.2's R8 capped at metadata v2.1.0):

```bash
curl -s -o builder-8.9.2.jar \
  https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.9.2/builder-8.9.2.jar
javap -p -c -constants -classpath builder-8.9.2.jar \
  com.android.builder.dexing.R8Version
```

`VERSION_AGP_WAS_SHIPPED_WITH = "8.9.35"` → AGP 8.9.2 ships **R8 8.9.35**.

Inspecting R8 8.9.35's `kL` class (same shape as `gc2` in 8.13.19):

```java
g = kL({2, 0, 0}, false);   // stable max = 2.0.0
h = kL({2, 1, 0}, false);   // experimental max = 2.1.0
```

→ **R8 8.9.35 caps at metadata v2.1.0**, exactly matching the original
investigation (Engram #46 / #47).

## Conclusion

| AGP version | Bundled R8 | Max metadata version | Compatible with Kotlin 2.3.0 |
|-------------|------------|----------------------|------------------------------|
| 8.9.2       | 8.9.35     | 2.1.0                | **NO** — 1171 warnings       |
| **8.13.2**  | **8.13.19**| **2.3.0**            | **YES** — primary path       |

Primary path (`agp = "8.13.2"`) **PASSES** verification. Fallback (9.2.1) is
not needed for this criterion; the design said to attempt fallback only if
primary failed.

---

# Post-bump assertion

After editing `gradle/libs.versions.toml` (`agp = "8.9.2"` → `"8.13.2"`):

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8
```

Result:

- **BUILD SUCCESSFUL** in 8m 56s, 99 tasks executed.
- `testDebugUnitTest`: **604 tests, 0 failures, 0 errors, 0 skipped.**
- `assembleDebug`: green.
- `detekt`: no new findings.
- `ktlintCheck`: green (only Kotlin compiler warnings unrelated to the bump;
  same set visible on master before the change).
- `:app:minifyReleaseWithR8`: green.

```bash
grep -cE "malformed kotlin\.Metadata|kotlin metadata version is not supported" /tmp/opencode/quality-gate.log
# → 0
```

The 1171-warning regression is gone. The pre-bump state in Engram #46
(1171 warnings) vs post-bump state (0 warnings) confirms AGP 8.13.2 / R8
8.13.19 is the fix.
