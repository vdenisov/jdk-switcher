# How JDK Switcher is tested

This records why the test suite is shaped the way it is. It was written as a plan and has been carried out in full; the design rationale is what is still worth keeping, so the ticket breakdown that used to live at the end has been removed.

The first version of the plan was built around Windows Server Core containers driven by Testcontainers. That was abandoned. Running Windows containers requires switching Docker Desktop to Windows virtualization, which shuts down the Linux engine and takes everything else running locally with it; the price is not worth what the tests give back.

## The decision

No containers, anywhere - not locally, not on CI. Tests are split into three tiers by the privileges they actually need, and each tier runs in the cheapest place that can host it:

| Tier | What it covers | Needs | Where it runs |
| --- | --- | --- | --- |
| 1. Pure logic | version ordering, directory name parsing, grouping by major version | nothing | local and CI, milliseconds |
| 2. Filesystem | symlink creation and removal, dangling links, exit codes, `latest` selection | symlink privilege | local (Developer Mode) and CI |
| 3. `jdk-init` | machine `PATH` and `JAVA_HOME`, `.bat` wrappers, real `java -version` | admin on a disposable machine | CI only |

The isolation that the container was there to provide is provided by two much cheaper mechanisms instead: an injected home directory for tiers 1 and 2, and the GitHub Actions runner itself (a throwaway Windows VM) for tier 3.

## Why not Windows containers, even on CI

Moving the container setup to CI does not work either. GitHub-hosted Windows runners do not support the `container:` directive; Windows container support in jobs was requested and never shipped (actions/runner [#904](https://github.com/actions/runner/issues/904) and [#1402](https://github.com/actions/runner/issues/1402)). You can build a Windows image on a hosted runner and push it to a registry, but you cannot run a job inside one.

There is also no need to. The hosted runner is already a disposable Windows VM, and job steps run elevated - UAC is disabled on the Windows images ([runner-images discussion #6557](https://github.com/actions/runner-images/discussions/6557)), so `mklink` and machine-scope `setx /M` work without any additional setup, and the VM is destroyed when the job ends. That is exactly the property we wanted from the container, minus the 4.5 GB image and the Docker mode switch.

## Tier 1. The logic that has no OS interaction at all is tested in-process.

Most of what broke on 2026-08-29 was pure logic: the version comparison collapsed every version into `major * 1000000 + minor * 1000 + patch`, so `25.0.4` and `25.0.4.1` scored identically and `25.0.1000` sorted above `25.1.0`, and the enumeration listing sorted version *strings*, putting `25.0.10` before `25.0.4`. None of this needs a filesystem, a symlink, or a Docker daemon to test - it needs a list of version numbers and an assertion.

The obstacle is that this logic currently lives in the body of `jdk-update.groovy`, which cannot be loaded without running it (it checks symlink capability and calls `System.exit` on the way through). `common.groovy`, on the other hand, already ends with `return this`, which means `evaluate(new File('common.groovy'))` hands back a live object whose methods can be called directly from a Spock spec. Moving `compareVersions`, `findLatestJdk` and the directory scan into `common.groovy` makes them reachable without any of the machinery around them.

Note that this is a genuine refactoring of production code, not a test-only accommodation - the three scripts already share `common.groovy` for symlink handling, and version parsing belongs there for the same reason.

## Tier 2. Filesystem tests run against an injected home directory, in a temp folder.

Both paths the scripts touch are injectable, which makes a real sandbox possible without changing any behaviour:

* `jdks.base.dir` is resolved against `System.getProperty('user.home')`, and the Groovy launcher passes `-D` through to the JVM. Verified: `groovy -Duser.home=C:/sandbox/fakehome uh.groovy` prints `C:/sandbox/fakehome`;
* `jdks.symlink.path` is read from `config.properties` next to the script, so a copy of the scripts with a rewritten config points the active symlink wherever we want.

There are no other hardcoded paths in `jdk-update.groovy` or `jdks.groovy`, so the two injection points together are sufficient. A tier 2 test does the following:

1. Create a temp directory and copy `*.groovy`, `*.bat` and `config.properties` into it.
2. Rewrite `jdks.symlink.path` in the copied config to a path inside the temp directory.
3. Build a mock `.jdks` tree under the temp home - directories named `<vendor>-<version>`, each with a `bin\java.bat` that echoes a version string when called with `--version`.
4. Run `groovy -Duser.home=<tmp> <tmp>\jdk-update.groovy`, capturing exit code, stdout and stderr as a separate process (a separate process is required regardless, because the scripts call `System.exit`).
5. Assert on the resulting symlinks with `Files.readSymbolicLink`, and on the process exit code and stderr.

The real `~/.jdks` and `C:\jdk` are never referenced, so even a script that goes completely wrong can only damage a temp folder. This is the tier that covers the dangling symlink bug, where `File#exists` follows the link and reports `false` for a stale one, so the removal was skipped and `mklink` then failed on the leftover directory entry.

Tier 2 needs the privilege to create symlinks. Developer Mode is enough for this and does not touch Docker in any way; it is enabled here, and `mklink /D` does succeed unelevated, which the whole tier 2 suite running locally confirms. On GitHub Actions it comes for free with the elevated runner. Note that reading `AllowDevelopmentWithoutDevLicense` under `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock` needs elevation itself and returns nothing without it, so an empty read there proves nothing either way.

## Tier 3. `jdk-init` is only ever run on a machine we are willing to lose.

`jdk-init.groovy` writes machine-scope `PATH` and `JAVA_HOME` through PowerShell with a `reg add` fallback. There is no injection point for that, and inventing one purely for tests would mean changing production behaviour to suit the test suite, which I would rather not do. So this tier runs on the GitHub Actions runner and nowhere else, gated on `GITHUB_ACTIONS` together with `RUNNER_ENVIRONMENT == 'github-hosted'` - `CI` alone is set by self-hosted runners and by anyone who exports it, and those hosts are not disposable. The spec snapshots the machine `JAVA_HOME` and `PATH` and puts them back afterwards, because the values it writes point into a temp directory that is deleted when the spec ends.

Important: that guard is the only thing between a local test run and a rewritten system `PATH`. It goes on the spec class, not on individual features, and it is not optional.

In exchange, tier 3 is the only tier that tests the whole thing as a user sees it: the admin check, `PATH` assembly, `JAVA_HOME`, the `.bat` wrappers, and `java -version` actually resolving through `C:\jdk\bin`.

The workflow is `windows-latest`, `actions/setup-java` for the JDKs, then `./gradlew test`. No Groovy installation is needed anywhere: the scripts are invoked as `java -cp <scriptRuntime configuration> groovy.ui.GroovyMain`, which resolves `scriptDir`, `args` and `user.home` exactly like the `groovy` launcher does. `jdk-init` is the one script that shells out to `groovy` by name for its two child scripts, and `ScriptSandbox` handles that by putting a `groovy.bat` shim on the child's `PATH` - the shim carries the sandboxed `user.home`, which a real installation would not, so the children cannot escape into the real `.jdks` either.

## What gets removed

The container scaffolding goes, along with the pre-built image apparatus in section 2.3 of the old plan (ghcr.io publishing, PAT setup, making the package public) which existed solely to make a 4.5 GB image tolerable:

* `src/test/resources/Dockerfile`, and with it the whole `src/test/resources` directory;
* `BaseContainerSpec.groovy` and the container-era `JdkInitSpec.groovy`;
* `helper/ContainerHelper.groovy`, `helper/JdkMockHelper.groovy` and `helper/AssertionHelper.groovy`;
* `.dockerignore` and `logback-test.xml`;
* the two `org.testcontainers` dependencies and `ch.qos.logback:logback-classic` in `build.gradle`.

That is roughly 590 lines of scaffolding. All three helpers turned out to be replaceable by a single `ScriptSandbox` rather than reworkable - once the scripts run as an ordinary local process, creating a mock JDK is `mkdirs()` and asserting on a symlink is `Files.readSymbolicLink`, so there is nothing left for a helper class to wrap. Logback went with them, since nothing on the test path logs through SLF4J any more.

Note that the mock JDKs `ScriptSandbox` builds are empty directories. The fake `bin\java.bat` responding to `--version` is only needed once tier 3 runs `java -version` through the active symlink, and gets added with that ticket.

## The Groovy version axis

The scripts are also run against Groovy 3.0.25, 4.0.33 and 5.0.8, selected with `-PgroovyVersion`. This is not because the code is version-sensitive - it uses `=~`, `tokenize`, `withDefault` and `String#execute`, all of which have been stable since Groovy 1.x, and all twelve Groovy-by-JDK combinations pass unchanged. The axis exists to catch the one failure that actually happened: Groovy 4.0.23 cannot run on JDK 25 at all, failing with `Unsupported class file major version 69`, because its bundled ASM predates that class file format. Newer patch releases on the same line (4.0.33) are fine. That is a class of breakage which arrives with a *new JDK*, not with a change to this repository, so a periodic matrix is the only thing that would notice it.

## Alternatives considered

| Alternative | Verdict | Reason | Revisit when |
| --- | --- | --- | --- |
| Windows containers locally | rejected | requires Docker Desktop in Windows mode, which disables the Linux engine and breaks unrelated local work | Docker supports both engines concurrently |
| Windows containers on hosted runners | not possible | `container:` is unsupported on Windows runners; images can be built but not run as jobs | actions/runner#904 is implemented |
| Windows Sandbox for tier 3 locally | deferred | disposable and shares the hypervisor with WSL2, so no Docker mode switch is needed; but there is no exec API and no exit codes, so results have to be scraped from a mapped folder | CI turns out to be too slow a feedback loop for `jdk-init` work |
| Self-hosted Windows runner with containers | rejected | the same Docker mode problem, except now on a machine I have to maintain myself | never, most likely |
| Stubbing the privilege and env-var calls in the scripts | rejected | changes production code to suit the tests; tier 2 sandboxing gets the same coverage without touching behaviour | - |
