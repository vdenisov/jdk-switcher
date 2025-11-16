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

    // Check if path is absolute or relative
    def pathFile = new File(pathArg)
    if (pathFile.isAbsolute()) {
        specificJdkPath = pathFile.absolutePath
    } else {
        specificJdkPath = new File(jdksDir, pathArg).absolutePath
    }

    // Validate the specified path exists
    if (!new File(specificJdkPath).exists()) {
        System.err.println("Error: Specified JDK directory does not exist: ${specificJdkPath}")
        System.exit(1)
    }
}

// Parse JDK directory names and group by major version
def jdksByMajor = [:].withDefault { [] }

jdksDir.listFiles().each { file ->
    if (file.isDirectory() && !file.name.isNumber()) {
        // Parse format: <vendor>-<version>
        def matcher = file.name =~ /^(.+)-(\d+(?:\.\d+)*(?:\.\d+)?)$/
        if (matcher) {
            def vendor = matcher[0][1]
            def version = matcher[0][2]
            def versionParts = version.tokenize('.')
            def majorVersion = versionParts[0].toInteger()

            jdksByMajor[majorVersion] << [
                vendor: vendor,
                version: version,
                versionParts: versionParts.collect { it.toInteger() },
                majorVersion: majorVersion,
                directory: file
            ]
        }
    }
}

if (jdksByMajor.isEmpty()) {
    println("No JDK installations found in ${jdksDir.absolutePath}")
    System.exit(0)
}

// Enumerate discovered JDKs (skip if explicit path provided)
if (!specificJdkPath) {
    println("Found JDK installations:")
    jdksByMajor.sort().each { majorVersion, jdks ->
        println("  JDK ${majorVersion}:")
        jdks.sort { it.version }.each { jdk ->
            println("    - ${jdk.vendor}-${jdk.version}")
        }
    }
    println()
}

// Helper function to find latest JDK from a list
def findLatestJdk(jdks) {
    return jdks.max { jdk ->
        def parts = jdk.versionParts + [0, 0, 0]
        parts[0] * 1000000 + parts[1] * 1000 + parts[2]
    }
}

// Helper function to create/update symlink
def updateSymlink(common, jdksDir, majorVersion, targetPath, description) {
    def symlinkPath = new File(jdksDir, majorVersion.toString()).absolutePath

    // Remove existing symlink if it exists
    if (new File(symlinkPath).exists()) {
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

// Handle specific version update with explicit path
if (specificJdkPath) {
    def success = updateSymlink(common, jdksDir, specificMajorVersion, specificJdkPath, specificJdkPath)
    if (success) {
        println("\nSymlink updated successfully!")
    }
    System.exit(success ? 0 : 1)
}

// Handle specific version update (find latest)
if (specificMajorVersion) {
    if (!jdksByMajor.containsKey(specificMajorVersion)) {
        System.err.println("Error: No JDK installations found for major version ${specificMajorVersion}")
        System.exit(1)
    }

    def latest = findLatestJdk(jdksByMajor[specificMajorVersion])
    def success = updateSymlink(common, jdksDir, specificMajorVersion, latest.directory.absolutePath, "${latest.vendor}-${latest.version}")
    if (success) {
        println("\nSymlink updated successfully!")
    }
    System.exit(success ? 0 : 1)
}

// Update all major versions (default behavior with no arguments)
jdksByMajor.each { majorVersion, jdks ->
    def latest = findLatestJdk(jdks)
    updateSymlink(common, jdksDir, majorVersion, latest.directory.absolutePath, "${latest.vendor}-${latest.version}")
}

println("\nSymlink update complete!")
