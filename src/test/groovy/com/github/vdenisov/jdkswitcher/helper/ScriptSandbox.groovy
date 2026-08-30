package com.github.vdenisov.jdkswitcher.helper

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs the JDK Switcher scripts against a throwaway home directory.
 * Both paths the scripts touch are injectable - the JDKs directory is resolved against user.home,
 * and the active symlink comes from config.properties - so a sandboxed run cannot reach the real
 * environment even if a script misbehaves.
 */
class ScriptSandbox {

    private static final List<String> SCRIPTS =
        ['common.groovy', 'jdk-update.groovy', 'jdks.groovy', 'jdk-init.groovy',
         'jdk-common.bat', 'jdk-update.bat', 'jdks.bat', 'jdk-init.bat']

    /** JDK homes the scripts are executed under, one process each. */
    static final List<String> JDK_HOMES =
        (System.getProperty('jdk.homes') ?: System.getProperty('java.home')).tokenize(',')

    final File home
    final File jdksDir
    final File activeJdk

    private final File scriptDir

    ScriptSandbox(File home) {
        this.home = home
        this.jdksDir = new File(home, '.jdks')
        // Two consecutive spaces on purpose: whitespace-splitting command execution collapses them
        // and silently links somewhere else, so every test through here guards against that
        this.activeJdk = new File(home, 'active  jdk')
        this.scriptDir = new File(home, 'scripts')

        jdksDir.mkdirs()
        scriptDir.mkdirs()
        SCRIPTS.each { new File(scriptDir, it).text = new File(it).text }
        writeConfig()
    }

    /**
     * Create an empty JDK installation directory named the way vendors name them
     * Use createRealJdk instead when the test needs java to actually run.
     * @param name Directory name in <vendor>-<version> form
     *
     * @return the created directory
     */
    File createJdk(String name) {
        def jdk = new File(jdksDir, name)
        jdk.mkdirs()
        return jdk
    }

    /**
     * Create a JDK installation that really runs, by copying in a minimal jlink image
     *
     * It is copied rather than symlinked on purpose. A symlink pointing out of the temp tree is
     * followed by @TempDir cleanup, which is how a real JDK on this machine was once deleted.
     * @param name Directory name in <vendor>-<version> form
     *
     * @return the created directory
     */
    File createRealJdk(String name) {
        def jdk = new File(jdksDir, name)
        copyTree(realJdkImage(), jdk)
        return jdk
    }

    /**
     * Build, once per checkout, a minimal but genuine JDK for tests that need to run java
     *
     * java.base alone is enough to answer -version, and comes to about 47 MB against the 291 MB of
     * a full installation. It lands under build/ so that a clean rebuilds it.
     *
     * @return the image directory
     */
    static synchronized File realJdkImage() {
        def image = new File('build/test-jdk')

        if (new File(image, 'bin/java.exe').exists()) {
            return image
        }

        image.deleteDir()
        def jlink = new File(System.getProperty('java.home'), 'bin/jlink.exe')
        def process = [jlink.absolutePath, '--add-modules', 'java.base',
                       '--no-header-files', '--no-man-pages',
                       '--output', image.absolutePath].execute()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        if (process.exitValue() != 0) {
            throw new IllegalStateException("jlink failed (exit ${process.exitValue()}): ${stderr.toString().trim()}")
        }

        return image
    }

    /**
     * Run one of the scripts as a separate process, since every script ends in System.exit
     * @param script Script file name
     * @param args Command line arguments for the script
     * @param jdkHome JDK to run under, defaults to the first entry of JDK_HOMES
     *
     * @return map of exitCode, stdout and stderr
     */
    Map run(String script, List<String> args = [], String jdkHome = JDK_HOMES.first()) {
        writeGroovyShim(jdkHome)

        def command = [javaOf(jdkHome),
                       '-cp', System.getProperty('script.runtime.classpath'),
                       "-Duser.home=${home.absolutePath}".toString(),
                       'groovy.ui.GroovyMain',
                       new File(scriptDir, script).absolutePath] + args

        // jdk-init shells out to "groovy" by name, so the shim has to win the PATH lookup
        def builder = new ProcessBuilder(command)
        builder.environment().put('PATH', "${scriptDir.absolutePath};${System.getenv('PATH')}".toString())

        def process = builder.start()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        return [exitCode: process.exitValue(), stdout: stdout.toString(), stderr: stderr.toString()]
    }

    /**
     * Read a machine-scope environment variable, which is what jdk-init writes
     * @param name Variable name
     *
     * @return the raw value, empty when the variable is not set
     *
     * @throws IllegalStateException when the read itself fails, so that a broken probe cannot be
     *         mistaken for jdk-init having written nothing
     */
    static String machineEnv(String name) {
        def result = powershell("[Environment]::GetEnvironmentVariable('${name}', 'Machine')")
        return result.out.trim()
    }

    /**
     * Write a machine-scope environment variable, used to restore what jdk-init overwrote
     * @param name Variable name
     * @param value New value, a null or empty value removes the variable
     *
     * @throws IllegalStateException when the write fails
     */
    static void setMachineEnv(String name, String value) {
        def literal = value ? "'${value.replace("'", "''")}'" : '$null'
        powershell("[Environment]::SetEnvironmentVariable('${name}', ${literal}, 'Machine')")
    }

    /**
     * Read where a symlink points, without following it
     * @param link The symlink to read
     *
     * @return the raw target path, null if the path is not a symlink
     */
    String targetOf(File link) {
        return Files.isSymbolicLink(link.toPath()) ? Files.readSymbolicLink(link.toPath()).toString() : null
    }

    /**
     * Whether this machine can create directory symlinks at all, which requires either Developer
     * Mode or an elevated shell. Reuses the same check the scripts themselves perform.
     *
     * @return true if symlinks can be created
     */
    static boolean symlinksAvailable() {
        return new GroovyShell().evaluate(new File('common.groovy')).canCreateSymlinks()
    }

    private static void copyTree(File source, File target) {
        def from = source.toPath()
        def to = target.toPath()

        Files.walk(from).withCloseable { paths ->
            paths.forEach { path ->
                def destination = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private static Map powershell(String script) {
        def process = ['powershell', '-NoProfile', '-Command', script].execute()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                "PowerShell failed (exit ${process.exitValue()}) for [${script}]: ${stderr.toString().trim()}")
        }

        return [out: stdout.toString(), err: stderr.toString()]
    }

    private static String javaOf(String jdkHome) {
        return new File(jdkHome, 'bin/java.exe').absolutePath
    }

    /**
     * jdk-init runs "cmd /c groovy <script>" for its two child scripts. Rather than requiring a
     * Groovy installation, put a shim on the PATH that reaches the same runtime and, critically,
     * carries the sandboxed user.home so the children cannot escape into the real .jdks.
     */
    private void writeGroovyShim(String jdkHome) {
        new File(scriptDir, 'groovy.bat').text = [
            '@echo off',
            "\"${javaOf(jdkHome)}\" -cp \"${System.getProperty('script.runtime.classpath')}\" " +
                "\"-Duser.home=${home.absolutePath}\" groovy.ui.GroovyMain %*"
        ].join('\r\n') + '\r\n'
    }

    private void writeConfig() {
        // Written through Properties so that backslashes in the Windows path get escaped
        def config = new Properties()
        config.setProperty('jdks.base.dir', '.jdks')
        config.setProperty('jdks.symlink.path', activeJdk.absolutePath)
        new File(scriptDir, 'config.properties').withOutputStream { config.store(it, null) }
    }
}
