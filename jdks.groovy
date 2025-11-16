#!/usr/bin/env groovy

// Load configuration
def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
def configFile = new File(scriptDir, 'config.properties')
def config = new Properties()
configFile.withInputStream { config.load(it) }

def SYMLINK_PATH = config.getProperty('jdks.symlink.path')
def JDKS_BASE_DIR = config.getProperty('jdks.base.dir')

// Validate command line arguments
if (args.length != 1) {
    System.err.println("Usage: jdks <jdk_version|latest>")
    System.err.println("Example: jdks 11")
    System.err.println("Example: jdks latest")
    System.exit(1)
}

// Load common utilities
def common = evaluate(new File(scriptDir, 'common.groovy'))

// Check symlink capability
if (!common.canCreateSymlinks()) {
    common.exitWithSymlinkError()
}

def userHome = System.getProperty('user.home')
def jdksDir = new File(userHome, JDKS_BASE_DIR)
def jdkVersion = args[0]

// Handle 'latest' keyword
if (jdkVersion.toLowerCase() == 'latest') {
    // Check if jdksDir exists and is a directory
    if (!jdksDir.exists() || !jdksDir.isDirectory()) {
        System.err.println("Error: JDKs base directory does not exist or is not a directory: ${jdksDir.absolutePath}")
        System.exit(1)
    }

    // Find all numeric symlinks in .jdks directory
    def files = jdksDir.listFiles()
    if (files == null) {
        System.err.println("Error: Unable to list files in JDKs base directory: ${jdksDir.absolutePath}")
        System.exit(1)
    }

    def majorVersions = files
        .findAll { it.isDirectory() && it.name.isNumber() }
        .collect { it.name.toInteger() }

    if (majorVersions.isEmpty()) {
        System.err.println("Error: No JDK version symlinks found in ${jdksDir.absolutePath}")
        System.err.println("Please run 'jdk-update' first to create version symlinks")
        System.exit(1)
    }

    jdkVersion = majorVersions.max().toString()
    println("Selected latest JDK version: ${jdkVersion}")
}

def targetPath = new File(userHome, "${JDKS_BASE_DIR}\\${jdkVersion}").absolutePath

// Check if target JDK directory exists
def targetDir = new File(targetPath)
if (!targetDir.exists()) {
    System.err.println("Error: Target JDK directory does not exist: ${targetPath}")
    System.exit(1)
}

// Remove existing symlink if it exists
if (new File(SYMLINK_PATH).exists()) {
    println("Removing existing symlink at ${SYMLINK_PATH}")
    if (!common.removeSymlink(SYMLINK_PATH)) {
        System.exit(1)
    }
}

// Create new symlink
println("Creating symlink: ${SYMLINK_PATH} -> ${targetPath}")
if (!common.createSymlink(SYMLINK_PATH, targetPath)) {
    System.exit(1)
}

println("Successfully switched to JDK ${jdkVersion}")
