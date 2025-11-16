#!/usr/bin/env groovy

// Load configuration
def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
def configFile = new File(scriptDir, 'config.properties')
def config = new Properties()
configFile.withInputStream { config.load(it) }

def JDKS_BASE_DIR = config.getProperty('jdks.base.dir')
def SYMLINK_PATH = config.getProperty('jdks.symlink.path')

// Load common utilities
def common = evaluate(new File(scriptDir, 'common.groovy'))

println("=== JDK Environment Initialization ===\n")

// Check for administrator privileges
if (!common.isAdmin()) {
    System.err.println("ERROR: Administrator privileges required.")
    System.err.println("")
    System.err.println("The initialization script needs to modify system environment variables")
    System.err.println("(JAVA_HOME and PATH), which requires administrator privileges.")
    System.err.println("")
    System.err.println("Please run this command from an elevated command prompt (Run as Administrator).")
    System.exit(1)
}

// Check symlink capability
if (!common.canCreateSymlinks()) {
    System.err.println("ERROR: Unable to create symlinks.")
    System.err.println("")
    System.err.println("Even with administrator privileges, symlink creation failed.")
    System.err.println("This is unusual. Please check your system configuration.")
    System.exit(1)
}

def userHome = System.getProperty('user.home')
def jdksDir = new File(userHome, JDKS_BASE_DIR)

println("✓ Running with administrator privileges")
println("✓ Symlink creation capability confirmed\n")

// Step 1: Check if .jdks directory exists
println("Step 1: Checking JDKs directory...")
if (!jdksDir.exists() || !jdksDir.isDirectory()) {
    System.err.println("ERROR: JDKs directory does not exist: ${jdksDir.absolutePath}")
    System.err.println("Please create this directory and install JDKs there first.")
    System.exit(1)
}
println("  ✓ JDKs directory found: ${jdksDir.absolutePath}\n")

// Step 2: Check and update PATH environment variable
println("Step 2: Checking PATH environment variable...")
def jdkBinPath = "${SYMLINK_PATH}\\bin"

// Read current system PATH
def getPathCmd = 'powershell -Command "[Environment]::GetEnvironmentVariable(\'Path\', \'Machine\')"'
def getPathProcess = getPathCmd.execute()
getPathProcess.waitFor()
def currentPath = getPathProcess.text.trim()

if (currentPath.toLowerCase().contains(jdkBinPath.toLowerCase())) {
    println("  ✓ JDK bin path already in PATH: ${jdkBinPath}\n")
} else {
    println("  Adding JDK bin path to PATH: ${jdkBinPath}")
    def newPath = "${jdkBinPath};${currentPath}"
    def setPathCmd = "powershell -Command \"[Environment]::SetEnvironmentVariable('Path', '${newPath}', 'Machine')\""
    def setPathProcess = setPathCmd.execute()
    setPathProcess.waitFor()

    if (setPathProcess.exitValue() != 0) {
        System.err.println("  ERROR: Failed to update PATH")
        System.err.println(setPathProcess.err.text)
        System.exit(1)
    }
    println("  ✓ PATH updated successfully\n")
}

// Step 3: Check and set JAVA_HOME environment variable
println("Step 3: Checking JAVA_HOME environment variable...")
def getJavaHomeCmd = 'powershell -Command "[Environment]::GetEnvironmentVariable(\'JAVA_HOME\', \'Machine\')"'
def getJavaHomeProcess = getJavaHomeCmd.execute()
getJavaHomeProcess.waitFor()
def currentJavaHome = getJavaHomeProcess.text.trim()

if (currentJavaHome && currentJavaHome.equals(SYMLINK_PATH)) {
    println("  ✓ JAVA_HOME already set correctly: ${SYMLINK_PATH}\n")
} else {
    if (currentJavaHome) {
        println("  Updating JAVA_HOME from: ${currentJavaHome}")
        println("                       to: ${SYMLINK_PATH}")
    } else {
        println("  Setting JAVA_HOME to: ${SYMLINK_PATH}")
    }

    def setJavaHomeCmd = "powershell -Command \"[Environment]::SetEnvironmentVariable('JAVA_HOME', '${SYMLINK_PATH}', 'Machine')\""
    def setJavaHomeProcess = setJavaHomeCmd.execute()
    setJavaHomeProcess.waitFor()

    if (setJavaHomeProcess.exitValue() != 0) {
        System.err.println("  ERROR: Failed to set JAVA_HOME")
        System.err.println(setJavaHomeProcess.err.text)
        System.exit(1)
    }
    println("  ✓ JAVA_HOME set successfully\n")
}

// Step 4: Run jdk-update script
println("Step 4: Running jdk-update to create version symlinks...")
def updateJdksCmd = "cmd /c groovy \"${scriptDir}\\jdk-update.groovy\""
def updateJdksProcess = updateJdksCmd.execute()
updateJdksProcess.waitForProcessOutput(System.out, System.err)

if (updateJdksProcess.exitValue() != 0) {
    System.err.println("ERROR: jdk-update script failed")
    System.exit(1)
}
println()

// Step 5: Run jdks script with 'latest'
println("Step 5: Switching to latest JDK version...")
def switchJdkCmd = "cmd /c groovy \"${scriptDir}\\jdks.groovy\" latest"
def switchJdkProcess = switchJdkCmd.execute()
switchJdkProcess.waitForProcessOutput(System.out, System.err)

if (switchJdkProcess.exitValue() != 0) {
    System.err.println("ERROR: jdks script failed")
    System.exit(1)
}

println("\n=== Initialization Complete! ===")
println("\nIMPORTANT NOTES:")
println("1. You must restart your terminal, command prompt, or IDE for the")
println("   environment variable changes to take effect.")
println("2. It is recommended to manually remove any existing JDK bin directories")
println("   from your PATH to avoid conflicts with the new symlink-based setup.")
println("\nYou can now use 'jdks <version>' to change JDK versions.")
