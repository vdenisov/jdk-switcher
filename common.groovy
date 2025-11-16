#!/usr/bin/env groovy

/**
 * Common utility functions for JDK Switcher scripts
 */

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
        def mklinkCmd = "cmd /c mklink /D \"${testSymlink.absolutePath}\" \"${testTarget.absolutePath}\""
        def mklinkProcess = mklinkCmd.execute()
        mklinkProcess.waitFor()

        def success = mklinkProcess.exitValue() == 0

        // Clean up
        if (testSymlink.exists()) {
            "cmd /c rmdir \"${testSymlink.absolutePath}\"".execute().waitFor()
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
        def cmd = 'net session'
        def process = cmd.execute()
        process.waitFor()
        return process.exitValue() == 0
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
    def symlinkFile = new File(symlinkPath)
    if (!symlinkFile.exists()) {
        return true
    }

    def removeCmd = "cmd /c rmdir \"${symlinkPath}\""
    def removeProcess = removeCmd.execute()
    removeProcess.waitFor()

    if (removeProcess.exitValue() != 0) {
        System.err.println("Error removing symlink ${symlinkPath}:")
        System.err.println(removeProcess.err.text)
        return false
    }

    // Show success output if available
    def output = removeProcess.text.trim()
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
    def mklinkCmd = "cmd /c mklink /D \"${symlinkPath}\" \"${targetPath}\""
    def mklinkProcess = mklinkCmd.execute()
    mklinkProcess.waitFor()

    if (mklinkProcess.exitValue() != 0) {
        System.err.println("Error creating symlink ${symlinkPath}:")
        System.err.println(mklinkProcess.err.text)
        return false
    }

    // Show success output from mklink (e.g., "symbolic link created for...")
    def output = mklinkProcess.text.trim()
    if (output) {
        println("  ${output}")
    }

    return true
}

// Return this binding to make functions available to importing scripts
return this
