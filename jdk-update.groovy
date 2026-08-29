#!/usr/bin/env groovy

// Load configuration
def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
def configFile = new File(scriptDir, 'config.properties')
def config = new Properties()
configFile.withInputStream { config.load(it) }

def JDKS_BASE_DIR = config.getProperty('jdks.base.dir')

// Load common utilities
def common = evaluate(new File(scriptDir, 'common.groovy'))

// Check symlink capability
if (!common.canCreateSymlinks()) {
    common.exitWithSymlinkError()
}

def userHome = System.getProperty('user.home')
def jdksDir = new File(userHome, JDKS_BASE_DIR)

// Validate JDKs directory exists
if (!jdksDir.exists() || !jdksDir.isDirectory()) {
    System.err.println("Error: JDKs directory does not exist: ${jdksDir.absolutePath}")
    System.exit(1)
}

// Parse command line arguments
def specificMajorVersion = null
def specificJdkPath = null

if (args.length == 1) {
    // Case 1: Major version specified - update to latest for that version
    specificMajorVersion = args[0].toInteger()
} else if (args.length == 2) {
    // Case 2: Major version + explicit JDK directory
    specificMajorVersion = args[0].toInteger()
    def pathArg = args[1]

    // Normalize the path (resolve .., ., etc.)
    def pathFile = new File(pathArg)
    try {
        if (pathFile.isAbsolute()) {
            specificJdkPath = pathFile.canonicalPath
        } else {
            specificJdkPath = new File(jdksDir, pathArg).canonicalPath
        }
    } catch (IOException e) {
        System.err.println("Error: Invalid path '${pathArg}': ${e.message}")
        System.exit(1)
    }

    // Validate the specified path exists
    def targetDir = new File(specificJdkPath)
    if (!targetDir.exists()) {
        System.err.println("Error: JDK directory does not exist: ${specificJdkPath}")
        System.exit(1)
    }

    if (!targetDir.isDirectory()) {
        System.err.println("Error: Path is not a directory: ${specificJdkPath}")
        System.exit(1)
    }

    // Validate that non-absolute path is under jdksDir
    // This prevents symlinks pointing to unexpected locations
    if (!pathFile.isAbsolute()) {
        def canonicalJdksDir = jdksDir.canonicalPath
        if (!specificJdkPath.startsWith(canonicalJdksDir)) {
            System.err.println("Warning: Specified path is outside .jdks directory: ${specificJdkPath}")
            System.err.println("This may cause issues with JDK version management.")
        }
    }
}

// Handle specific version update with explicit path
// Handled before discovery, as an explicitly given target does not depend on what is installed
if (specificJdkPath) {
    def success = updateSymlink(common, jdksDir, specificMajorVersion, specificJdkPath, specificJdkPath)
    if (success) {
        println("\nSymlink updated successfully!")
    }
    System.exit(success ? 0 : 1)
}

// Parse JDK directory names and group by major version
def jdksByMajor = common.discoverJdks(jdksDir)

if (jdksByMajor.isEmpty()) {
    println("No JDK installations found in ${jdksDir.absolutePath}")
    System.exit(0)
}

// Enumerate discovered JDKs
println("Found JDK installations:")
jdksByMajor.sort().each { majorVersion, jdks ->
    println("  JDK ${majorVersion}:")
    jdks.sort { a, b -> common.compareVersions(a.versionParts, b.versionParts) }.each { jdk ->
        println("    - ${jdk.vendor}-${jdk.version}")
    }
}
println()

// Helper function to create/update symlink
def updateSymlink(common, jdksDir, majorVersion, targetPath, description) {
    def symlinkPath = new File(jdksDir, majorVersion.toString()).absolutePath

    // Remove existing symlink if it exists; NOFOLLOW so a dangling symlink is still removed,
    // otherwise mklink fails on the leftover directory entry
    if (common.pathExists(symlinkPath)) {
        println("Removing existing symlink: ${symlinkPath}")
        if (!common.removeSymlink(symlinkPath)) {
            return false
        }
    }

    // Create new symlink
    println("Creating symlink: ${majorVersion} -> ${description}")
    if (!common.createSymlink(symlinkPath, targetPath)) {
        return false
    }

    println("  ✓ JDK ${majorVersion} -> ${description}")
    return true
}

// Handle specific version update (find latest)
if (specificMajorVersion) {
    if (!jdksByMajor.containsKey(specificMajorVersion)) {
        System.err.println("Error: No JDK installations found for major version ${specificMajorVersion}")
        System.exit(1)
    }

    def latest = common.findLatestJdk(jdksByMajor[specificMajorVersion])
    def success = updateSymlink(common, jdksDir, specificMajorVersion, latest.directory.absolutePath, "${latest.vendor}-${latest.version}")
    if (success) {
        println("\nSymlink updated successfully!")
    }
    System.exit(success ? 0 : 1)
}

// Update all major versions (default behavior with no arguments)
def failedVersions = []

jdksByMajor.each { majorVersion, jdks ->
    def latest = common.findLatestJdk(jdks)
    if (!updateSymlink(common, jdksDir, majorVersion, latest.directory.absolutePath, "${latest.vendor}-${latest.version}")) {
        failedVersions << majorVersion
    }
}

if (failedVersions) {
    System.err.println("\nERROR: Failed to update symlinks for JDK ${failedVersions.sort().join(', ')}")
    System.exit(1)
}

println("\nSymlink update complete!")
