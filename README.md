# JDK Switcher

![Windows](https://img.shields.io/badge/Windows-0078D6?style=flat&logo=windows&logoColor=white)
![Groovy](https://img.shields.io/badge/Groovy-4298B8?style=flat&logo=apache-groovy&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

**Fast JDK version switching for Windows via directory symlinks**

---

## Motivation

This project was born out of my personal frustration with having to manually switch between different JDK versions on Windows. The traditional approach of constantly updating `JAVA_HOME` and `PATH` environment variables, followed by restarting terminals, command prompts, and IDEs, was way too time-consuming and error-prone. I wanted a solution that would allow me to switch JDK versions instantly without any restarts or manual environment variable manipulation.

JDK Switcher solves this problem by using a single directory symlink (`C:\jdk` or similar) that can be quickly redirected to any installed JDK version. Since the environment variables are configured once and point to this symlink, switching JDK versions becomes a simple matter of updating the symlink target—no restarts required.

---

## Features

- **Instant JDK switching** - Change between JDK versions without restarting terminals, command prompts, or IDEs
- **One-time setup** - Configure `JAVA_HOME` and `PATH` once, never touch them again
- **Version symlinks** - Automatically creates major version shortcuts (e.g., `11`, `17`, `21`) that point to the latest installed version of each JDK
- **Latest version quick-switch** - Quickly switch to the newest major JDK version with the `latest` keyword
- **JDK vendor-agnostic** - Works with any JDK distribution (Oracle, Temurin, Zulu, Microsoft, etc.)
- **Simple commands** - Three easy-to-use scripts for initialization, switching, and updating
- **IntelliJ IDEA compatible** - Default directory structure matches IntelliJ IDEA's JDK download location and naming convention

---

## Prerequisites

- **Windows 10/11** (or any other Windows version with symlink support)
- **Groovy** installed and available in PATH ([Download Groovy](https://groovy-lang.org/download.html))
- **Administrator privileges** (required for initial setup and creating symlinks by default)
  - **Alternative:** Enable Windows Developer Mode or grant `SeCreateSymbolicLinkPrivilege` to your user account to create symlinks without elevation
- One or more JDK installations

---

## Installation & Setup

### 1. Clone or Download This Repository

```bash
git clone https://github.com/vdenisov/jdk-switcher.git
cd jdk-switcher
```

Or download and extract the ZIP file to a local directory.

### 2. Add Scripts to Your PATH

Add the directory containing the JDK Switcher scripts to your system `PATH` so you can run them from anywhere.

### 3. Install Your JDKs

Install your JDK distributions into `%USERPROFILE%\.jdks\` using the naming convention `<vendor>-<version>`.

**Examples:**
- `temurin-17.0.12`
- `temurin-21.0.5`
- `zulu-11.0.25`
- `oracle-23.0.1`

**Note 1:** This default directory (`%USERPROFILE%\.jdks`) and naming convention is compatible with IntelliJ IDEA's automatic JDK downloads, making it easy to use JDKs managed by IntelliJ with this tool.

**Note 2:** You can use symlinks to link to other locations, not just `%USERPROFILE%\.jdks`.

### 4. Configure Settings

Edit `config.properties` to customize the installation paths if needed:

```properties
# Base directory where JDK installations are stored (relative to user home)
jdks.base.dir=.jdks

# Path where the active JDK symlink will be created
jdks.symlink.path=C:\\jdk
```

### 5. Run Initialization Script

Open an **elevated command prompt** (Run as Administrator) and execute:

```cmd
jdk-init
```

This script will:
1. Verify your `.jdks` directory exists
2. Add `C:\jdk\bin` to your system `PATH`
3. Set `JAVA_HOME` to `C:\jdk`
4. Create major version symlinks (e.g., `11`, `17`, `21`)
5. Switch to the latest JDK version

**⚠️ Important:** After initialization, restart your terminal, command prompt, or IDE for the environment variables to take effect.

---

## Configuration

The `config.properties` file contains two settings:

| Property | Description | Default Value |
|----------|-------------|---------------|
| `jdks.base.dir` | Directory where JDKs are installed (relative to `%USERPROFILE%`) | `.jdks` |
| `jdk.symlink.path` | Absolute path where the active JDK symlink will be created | `C:\jdk` |

**Example:**

```properties
jdks.base.dir=.jdks
jdk.symlink.path=C:\\jdk
```

---

## Usage

### Switch to a Specific JDK Version

```cmd
jdks 17
```

Switches to JDK 17 (uses the symlink `%USERPROFILE%\.jdks\17`, which points to the latest installed JDK 17).

### Switch to the Latest Installed JDK

```cmd
jdks latest
```

Automatically detects and switches to the highest major version available.

### Update Version Symlinks

After installing a new JDK, run:

```cmd
jdk-update
```

This scans your `.jdks` directory and updates the major version symlinks to point to the latest patch version for each major release.

**Example:** If you have `temurin-17.0.10` and `temurin-17.0.12`, the `17` symlink will point to `temurin-17.0.12`.

### Update a Specific Version Symlink

```cmd
jdk-update 17
```

Updates only the JDK 17 symlink to point to the latest installed JDK 17 version.

### Manually Set a Version Symlink

You can manually set a version symlink to point to a specific JDK installation using either a relative path (relative to `.jdks`) or an absolute path.

**Using a relative path:**
```cmd
jdk-update 17 temurin-17.0.10
```

**Using an absolute path (e.g., standard Temurin installation):**
```cmd
jdk-update 17 "C:\Program Files\Eclipse Adoptium\jdk-17.0.12.7-hotspot"
```

This flexibility allows you to manage JDKs installed in different locations, not just those in your `.jdks` directory.

---

## How It Works (Technical Overview)

JDK Switcher uses a **two-tier symlink architecture** to provide JDK switching without the need for configuration files or persistent version mappings.

### Tier 1: Major Version Symlinks

Located in `%USERPROFILE%\.jdks\`, these are numeric directory symlinks that point to specific JDK installations:

```
C:\Users\YourName\.jdks\
├── 11 → zulu-11.0.25
├── 17 → temurin-17.0.12
├── 21 → temurin-21.0.5
├── zulu-11.0.25\
├── temurin-17.0.12\
└── temurin-21.0.5\
```

The `jdk-update` script manages these symlinks, automatically pointing each major version to the latest installed patch version.

**Why major version symlinks?** This tier provides two key benefits:
1. **No configuration files needed** - The symlinks themselves store the version mappings directly in the filesystem
2. **Third-party tool integration** - Other tools (IDEs, build scripts, Docker files) can reference `%USERPROFILE%\.jdks\17` directly, and you can transparently upgrade minor JDK versions by simply running `jdk-update` without reconfiguring those tools

### Tier 2: Active JDK Symlink

A single directory symlink at `C:\jdk` points to one of the major version symlinks:

```
C:\jdk → C:\Users\YourName\.jdks\17
```

The `jdks` script updates this symlink to switch between JDK versions. This provides the global "active" JDK used by your system.

### Environment Variables

The system environment variables are configured once during initialization:

- **`JAVA_HOME`**: `C:\jdk`
- **`PATH`**: `C:\jdk\bin` (prepended as the very first entry, feel free to reorder if needed)

Since these variables point to the symlink, **no environment variable changes are needed** when switching versions. The symlink redirection happens instantly, and the new JDK version is immediately available in all open terminals and applications.

---

## License

This project is licensed under the MIT License.

---

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

---

## Acknowledgments

This tool was inspired by:
- Unix/Linux tools like `update-alternatives` for system-wide version management
- JDK management tools like SDKMAN! for their user-friendly approach to JDK switching
- The [`jvms`](https://github.com/ystyle/jvms) tool, from which I borrowed the symlink-based approach for JDK version management on Windows
