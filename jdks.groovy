#!/usr/bin/env groovy

// Load configuration
def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
def configFile = new File(scriptDir, 'config.properties')
def config = new Properties()
configFile.withInputStream { config.load(it) }

def SYMLINK_PATH = config.getProperty('jdks.symlink.path')
def JDKS_BASE_DIR = config.getProperty('jdks.base.dir')

// Usage goes to stdout when it was asked for and to stderr when it was provoked
def printUsage(PrintStream out) {
    out.println("Usage: jdks [<version>|latest|--help]")
    out.println("")
    out.println("  jdks             Show the active JDK and the versions available")
    out.println("  jdks <version>   Switch to a major version, for example: jdks 17")
    out.println("  jdks latest      Switch to the highest major version installed")
    out.println("  jdks --help      Show this message")
    out.println("")
    out.println("Related: jdk-update refreshes the version symlinks after installing a JDK,")
    out.println("         jdk-init sets up JAVA_HOME and PATH for the first time.")
}

// Validate command line arguments
if (args.length > 1) {
    System.err.println("Error: expected at most one argument, got ${args.length}")
    printUsage(System.err)
    System.exit(1)
}

// Note: /? is deliberately absent. The Java launcher glob-expands "?" on Windows, so the argument
// arrives as something like "\1" and never reaches this comparison.
if (args.length == 1 && args[0].toLowerCase() in ['--help', '-h', 'help']) {
    printUsage(System.out)
    System.exit(0)
}

// Load common utilities
def common = evaluate(new File(scriptDir, 'common.groovy'))

def userHome = System.getProperty('user.home')
def jdksDir = new File(userHome, JDKS_BASE_DIR)

// Find the numeric symlinks this tool creates, newest major version last
// Dangling ones are kept, so that a broken link is reported rather than quietly ignored
def findVersionLinks(common, File jdksDir) {
    def files = jdksDir.isDirectory() ? jdksDir.listFiles() : null

    return (files ?: [])
        .findAll { it.name.isNumber() && (it.isDirectory() || common.isSymlink(it.absolutePath)) }
        .sort { it.name.toInteger() }
}

// Resolve a symlink to the installation it ends up at
// File#canonicalPath does not follow symlinks on Windows, it returns the link's own path
def resolvedName(File path) {
    try {
        return path.toPath().toRealPath().fileName.toString()
    } catch (IOException e) {
        return null
    }
}

// Print what a JDK directory reports as its version, or say why it cannot
def printJavaVersion(common, String jdkPath, String indent) {
    def javaExe = new File(jdkPath, 'bin\\java.exe')

    if (!javaExe.exists()) {
        System.err.println("Warning: ${javaExe} not found, ${jdkPath} does not look like a JDK")
        return
    }

    def result = common.runCommand([javaExe.absolutePath, '-version'])
    def banner = common.firstNonBlankLine(result.err, result.out)

    if (result.exitCode == 0 && banner) {
        println("${indent}${banner}")
    } else {
        System.err.println("Warning: could not run ${javaExe}: ${result.err.trim()}")
    }
}

// No arguments: report the current state instead of failing with a usage message
if (args.length == 0) {
    def activeName = resolvedName(new File(SYMLINK_PATH))

    if (activeName) {
        println("Active JDK: ${activeName} (${SYMLINK_PATH})")
        printJavaVersion(common, SYMLINK_PATH, '  ')
    } else if (common.pathExists(SYMLINK_PATH)) {
        println("Active JDK: ${SYMLINK_PATH} is a dangling symlink, run 'jdks <version>' to repoint it")
    } else {
        println("Active JDK: none, ${SYMLINK_PATH} does not exist")
    }

    def links = findVersionLinks(common, jdksDir)
    if (links.isEmpty()) {
        println("\nNo version symlinks found in ${jdksDir.absolutePath}, run 'jdk-update' to create them")
        System.exit(0)
    }

    println("\nAvailable versions:")
    links.each { link ->
        def target = resolvedName(link)
        def marker = target && target == activeName ? '  (active)' : ''
        println("  ${link.name} -> ${target ?: 'dangling, run jdk-update'}${marker}")
    }

    // One line rather than the full usage, since this output is the common case
    println("\nRun 'jdks <version>' to switch, or 'jdks --help' for all options.")
    System.exit(0)
}

// Check symlink capability
if (!common.canCreateSymlinks()) {
    common.exitWithSymlinkError()
}

def jdkVersion = args[0]

// Handle 'latest' keyword
if (jdkVersion.toLowerCase() == 'latest') {
    // Check if jdksDir exists and is a directory
    if (!jdksDir.exists() || !jdksDir.isDirectory()) {
        System.err.println("Error: JDKs base directory does not exist or is not a directory: ${jdksDir.absolutePath}")
        System.exit(1)
    }

    def majorVersions = findVersionLinks(common, jdksDir).collect { it.name.toInteger() }

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
    if (common.isSymlink(targetPath)) {
        System.err.println("Error: JDK ${jdkVersion} symlink is dangling, its target no longer exists: ${targetPath}")
        System.err.println("Please run 'jdk-update' to repoint it at the installed JDK")
    } else {
        System.err.println("Error: Target JDK directory does not exist: ${targetPath}")
    }
    System.exit(1)
}

// Remove existing symlink if it exists; NOFOLLOW so a dangling symlink is still removed,
// otherwise mklink fails on the leftover directory entry
if (common.pathExists(SYMLINK_PATH)) {
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

// Report what the new symlink actually resolves to. This walks both symlink hops and proves the
// target really is a JDK, which the existence check above cannot tell.
printJavaVersion(common, SYMLINK_PATH, '  ')
