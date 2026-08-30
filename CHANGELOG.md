# Changelog

## 0.2.0

The release that came out of a JDK update breaking the tool, and it took two bugs compounding to do it.

Several vendors shipped security releases outside the usual cadence, so `temurin-11.0.32.1`, `corretto-21.0.12.1` and `temurin-25.0.4.1` were installed alongside the existing `11.0.32`, `21.0.12` and `25.0.4`. Running `jdk-update` at that point should have repointed each version symlink at the newer installation, but the version comparison scored `25.0.4` and `25.0.4.1` identically, so the symlinks stayed where they were. Once the superseded JDKs were removed, three of the four version symlinks were dangling - and re-running `jdk-update` could not repair them, because it could not see that they were broken.

Most of what follows is the fallout of chasing that down.

### Fixed

- **Dangling symlinks are repaired instead of skipped.** `File#exists` follows symlinks, so a link whose target no longer exists reported `false`. The removal step was skipped and `mklink` then failed on the leftover directory entry. Existence checks now use `Files.exists` with `NOFOLLOW_LINKS`.
- **`jdk-update` reports failure.** It discarded the result of every symlink update and printed `Symlink update complete!` regardless. It now exits non-zero and names the versions it could not link.
- **`jdks latest` no longer downgrades silently.** It filtered candidates with `isDirectory()`, which also follows links, so a broken symlink for the newest version made it quietly select an older one. It now reports the breakage.
- **Version ordering.** Versions were collapsed into `major * 1000000 + minor * 1000 + patch`, which tied `25.0.4` with `25.0.4.1` and sorted `25.0.1000` above `25.1.0`. The installation listing sorted version *strings*, printing `25.0.10` before `25.0.4`. Both now compare component by component.
- **`jdk-update <version> <path>` with an explicit path** ran after installation discovery, so an empty `.jdks` made it exit zero without creating the symlink that was asked for.
- **Paths containing runs of whitespace.** Commands were executed as a single string, which splits on whitespace and collapses runs of it, so `mklink` reported success while linking to a path that did not exist. Everything is executed as an argument list now.
- **Apostrophes in paths.** Writing `PATH` and `JAVA_HOME` interpolated the value into a single-quoted PowerShell literal without escaping, so a user directory such as `C:\Users\O'Brien` broke the command - and the registry fallback would then have written back a corrupted `PATH`.
- **A potential hang.** Commands waited for the process before reading its output, which deadlocks whenever the child writes more than the pipe buffer holds. Reading the machine `PATH` is exactly the case that can exceed it.

### Added

- **`jdks` with no arguments** shows the active JDK, the version it reports, and every version symlink available, marking the active one and flagging any that are dangling. It needs no symlink privileges, since it changes nothing.
- **`jdks --help`**, also accepted as `-h` and `help`. Note that `/?` is not supported: the Java launcher expands `?` as a wildcard on Windows, so the argument never arrives intact.
- **`jdks <version>` reports what it switched to**, by running `java -version` through the new symlink. This walks both symlink hops and confirms the target really is a JDK.
- **The `.bat` wrappers repair their own bootstrap.** Groovy needs a JDK, and `JAVA_HOME` normally points at the symlink these scripts manage; when that link broke, the tool could not start in order to fix it. The wrappers now fall back to an installed JDK under the JDKs directory.

### Changed

- `jdks` with no arguments used to print usage and exit 1. It now prints status and exits 0. Usage moved to `jdks --help`.

### Compatibility

Tested against Groovy 3.0, 4.0 and 5.0, on every LTS JDK from 11 to 25.

Note that a Groovy patch release can be too old for a JDK released after it: Groovy 4.0.23 cannot run on JDK 25 at all, failing with `Unsupported class file major version 69`. If Groovy will not start, upgrade it first.

### Development

Not part of the release archive, but new in this version: a Spock test suite split into three tiers by the privileges each needs, running on GitHub Actions across the whole Groovy and JDK matrix. See `CONTRIBUTING.md` and `docs/testing-design.md`.

## 0.1.0

Initial release. Two-tier symlink architecture, `jdk-init` for one-time environment setup, `jdk-update` to point major version symlinks at the newest installed patch release, and `jdks` to switch the active JDK.
