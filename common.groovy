#!/usr/bin/env groovy

import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Common utility functions for JDK Switcher scripts
 */

/**
 * Run a command and collect its output
 *
 * The command is given as an already split argument list, never as a single string. The string
 * form goes through Runtime#exec, which splits on whitespace and collapses runs of it, so a path
 * containing two consecutive spaces silently becomes a different path - and mklink then reports
 * success while creating a symlink to somewhere that does not exist.
 *
 * Both streams are drained while the process runs. A bare waitFor deadlocks whenever the child
 * writes more than the pipe buffer holds (roughly 4 KB on Windows), which the machine PATH alone
 * can exceed, so every command in these scripts goes through here.
 * @param command The executable followed by its arguments
 *
 * @return map of exitCode, out and err
 */
def runCommand(List<String> command) {
    def process = command.execute()
    def out = new StringWriter()
    def err = new StringWriter()
    process.waitForProcessOutput(out, err)

    return [exitCode: process.exitValue(), out: out.toString(), err: err.toString()]
}

/**
 * Return the first line of the given texts that is not blank
 *
 * Used to pick a command's banner without caring which stream it came out of - java writes its
 * -version output to stderr, not stdout.
 * @param texts The texts to scan, in order of preference
 *
 * @return the trimmed first non-blank line, null when there is none
 */
def firstNonBlankLine(String... texts) {
    return texts.collect { it ?: '' }
        .join('\n')
        .readLines()
        .find { it.trim() }
        ?.trim()
}

/**
 * Escape a value for embedding in a single-quoted PowerShell string literal
 *
 * A single quote is PowerShell's own escape character inside such a literal, and it is a perfectly
 * legal character in a Windows path, so a user directory like C:\Users\O'Brien puts one straight
 * into PATH and would otherwise produce a broken command.
 * @param value The value to escape, may be null
 *
 * @return the escaped value, empty for null
 */
def escapePowerShellLiteral(String value) {
    return value == null ? '' : value.replace("'", "''")
}

/**
 * Check whether a directory entry exists, without following symlinks
 * Unlike File#exists, this returns true for a dangling symlink, i.e. one whose
 * target has been renamed or removed
 * @param path The path to check
 *
 * @return true if the directory entry exists
 */
def pathExists(String path) {
    return Files.exists(new File(path).toPath(), LinkOption.NOFOLLOW_LINKS)
}

/**
 * Check whether a path is a symlink, regardless of whether its target resolves
 * @param path The path to check
 *
 * @return true if the path is a symlink
 */
def isSymlink(String path) {
    return Files.isSymbolicLink(new File(path).toPath())
}

/**
 * Check if the current user can create directory symlinks
 * Tests by attempting to create a temporary symlink and cleaning up afterward
 * @return true if symlinks can be created, false otherwise
 */
def canCreateSymlinks() {
    def tempDir = new File(System.getProperty('java.io.tmpdir'))
    def testSymlink = new File(tempDir, "jdk-switcher-test-${System.currentTimeMillis()}")
    def testTarget = new File(tempDir, "jdk-switcher-target-${System.currentTimeMillis()}")

    try {
        // Create a temporary target directory
        testTarget.mkdirs()

        // Try to create a symlink
        def success = runCommand(['cmd', '/c', 'mklink', '/D',
                                  testSymlink.absolutePath, testTarget.absolutePath]).exitCode == 0

        // Clean up
        if (testSymlink.exists()) {
            runCommand(['cmd', '/c', 'rmdir', testSymlink.absolutePath])
        }
        if (testTarget.exists()) {
            testTarget.delete()
        }

        return success
    } catch (Exception e) {
        return false
    }
}

/**
 * Check if the current process is running with administrator privileges
 * @return true if running as admin, false otherwise
 */
def isAdmin() {
    try {
        return runCommand(['net', 'session']).exitCode == 0
    } catch (Exception e) {
        return false
    }
}

/**
 * Display an error message about insufficient symlink privileges and exit
 */
def exitWithSymlinkError() {
    System.err.println("ERROR: Unable to create symlinks.")
    System.err.println("")
    System.err.println("This can happen for one of the following reasons:")
    System.err.println("1. You need to run this command from an elevated command prompt (Run as Administrator)")
    System.err.println("2. Enable Windows Developer Mode in Settings > Privacy & Security > For Developers")
    System.err.println("3. Grant SeCreateSymbolicLinkPrivilege to your user account via Local Security Policy")
    System.exit(1)
}

/**
 * Remove a directory symlink if it exists
 * @param symlinkPath The path to the symlink to remove
 * @return true if removal was successful or symlink didn't exist, false on error
 */
def removeSymlink(String symlinkPath) {
    if (!pathExists(symlinkPath)) {
        return true
    }

    def result = runCommand(['cmd', '/c', 'rmdir', symlinkPath])

    if (result.exitCode != 0) {
        System.err.println("Error removing symlink ${symlinkPath}:")
        System.err.println(result.err)
        return false
    }

    // Show success output if available
    def output = result.out.trim()
    if (output) {
        println(output)
    }

    return true
}

/**
 * Create a directory symlink
 * @param symlinkPath The path where the symlink should be created
 * @param targetPath The path the symlink should point to
 * @return true if creation was successful, false on error
 */
def createSymlink(String symlinkPath, String targetPath) {
    def result = runCommand(['cmd', '/c', 'mklink', '/D', symlinkPath, targetPath])

    if (result.exitCode != 0) {
        System.err.println("Error creating symlink ${symlinkPath}:")
        System.err.println(result.err)
        return false
    }

    // Show success output from mklink (e.g., "symbolic link created for...")
    def output = result.out.trim()
    if (output) {
        println("  ${output}")
    }

    return true
}

/**
 * Compare two version component lists, treating missing components as zero,
 * so 25.0.4.1 outranks 25.0.4 and 25.0.10 outranks 25.0.4
 * @param left Components of the left version
 * @param right Components of the right version
 *
 * @return negative, zero or positive as left is lower than, equal to or higher than right
 */
def compareVersions(List<Integer> left, List<Integer> right) {
    for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
        def result = (left[i] ?: 0) <=> (right[i] ?: 0)
        if (result != 0) {
            return result
        }
    }
    return 0
}

/**
 * Find the highest-versioned JDK in a list of entries produced by discoverJdks
 * @param jdks The entries to choose from
 *
 * @return the entry with the highest version, null if the list is empty
 */
def findLatestJdk(jdks) {
    return jdks.max { a, b -> compareVersions(a.versionParts, b.versionParts) }
}

/**
 * Scan a directory for JDK installations named <vendor>-<version>
 * Numerically named entries are skipped, as those are the version symlinks this tool creates
 * @param jdksDir The directory to scan
 *
 * @return map of major version to entries holding vendor, version, versionParts and directory
 */
def discoverJdks(File jdksDir) {
    def jdksByMajor = [:].withDefault { [] }

    jdksDir.listFiles().each { file ->
        if (file.isDirectory() && !file.name.isNumber()) {
            // Parse format: <vendor>-<version>
            def matcher = file.name =~ /^(.+)-(\d+(?:\.\d+)*(?:\.\d+)?)$/
            if (matcher) {
                def versionParts = matcher[0][2].tokenize('.').collect { it.toInteger() }

                jdksByMajor[versionParts[0]] << [
                    vendor: matcher[0][1],
                    version: matcher[0][2],
                    versionParts: versionParts,
                    directory: file
                ]
            }
        }
    }

    return jdksByMajor
}

// Return this binding to make functions available to importing scripts
return this
