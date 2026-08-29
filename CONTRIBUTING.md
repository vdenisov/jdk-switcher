# Contributing to JDK Switcher

The scripts themselves need nothing but a Groovy installation on Windows. Everything below is about the test suite, which is a Gradle build living alongside them and is excluded from release archives.

## What you need

* **JDK 17 or newer** to run Gradle. The build itself is pinned to a JDK 21 toolchain, so the version you launch Gradle with does not affect the result; Gradle auto-detects JDKs installed under `~/.jdks`, which is where IntelliJ puts them.
* **Windows Developer Mode**, or an elevated shell. Creating directory symlinks is what most of the suite does, and without the privilege those tests are skipped rather than failed. Settings > System > For developers > Developer Mode.
* **No Docker**, and no Groovy installation. An earlier version of the plan used Windows containers; see `PLAN.md` for why that was dropped.

## Running the tests

```
gradlew test
```

That runs everything except tier 3 (see below) and takes about a minute. To narrow it down:

```
gradlew test --tests CommonSpec
```

## The three tiers

Tests are split by the privileges they need, not by what they cover.

**Tier 1** (`CommonSpec`) exercises version ordering and installation discovery in process, by evaluating `common.groovy` and calling its methods directly. No symlinks, no subprocesses, runs in milliseconds.

**Tier 2** (`JdkUpdateSpec`, `JdksSpec`) runs `jdk-update` and `jdks` as real processes against a throwaway home directory. Both paths the scripts touch are injectable - `.jdks` is resolved against `user.home`, and the active symlink comes from `config.properties` - so `ScriptSandbox` copies the scripts into a temp directory with a rewritten config and runs them with `-Duser.home=<temp>`. A script that goes completely wrong can only damage a temp folder, never your real `.jdks`.

**Tier 3** (`JdkInitSpec`) covers `jdk-init`, which writes machine-scope `PATH` and `JAVA_HOME`. There is no way to inject those, so it is guarded with `@Requires({ env.CI })` and runs only on the GitHub Actions runner, which is a disposable elevated VM.

Important: that guard is the only thing between a local test run and a rewritten system `PATH`. Do *not* remove it to "just check something locally". If you need to work on `jdk-init`, push a branch and let CI run it, or use a throwaway VM.

## Testing other JDK and Groovy versions

The scripts have to work on every LTS JDK from 11 to 25, and are run as separate processes precisely so that the JDK and Groovy under test are independent of the ones running Gradle.

```
gradlew test "-Djdk.homes=C:\path\to\jdk-11,C:\path\to\jdk-17,C:\path\to\jdk-21,C:\path\to\jdk-25"
gradlew test -PgroovyVersion=4.0.33
```

`jdk.homes` is a comma-separated list and defaults to the JDK running the tests, so a plain `gradlew test` stays fast. `groovyVersion` defaults to 5.0.8 and accepts anything from 3.0 upwards (the group id changed at Groovy 4, the build handles that). CI runs the whole JDK matrix against each of Groovy 3.0.25, 4.0.33 and 5.0.8.

Note that a Groovy patch release can be too old for a JDK that came out after it - 4.0.23 cannot run on JDK 25 at all. This is why the Groovy axis exists; it is not because the scripts use anything version-specific.

## Code style

* Groovy scripts: 4 spaces, LF line endings (`.gitattributes` enforces this; `.bat` files stay CRLF).
* Specs: Spock `given`/`when`/`then`, one feature per behaviour, data tables where the same assertion repeats.
* Test names describe the behaviour, not the method.
* Private helpers go at the end of the file, after the features.

## Pull requests

Tests have to pass, new behaviour needs a test at the lowest tier that can cover it, and `README.md` gets updated when usage changes. The CI workflow runs on every pull request.
