package com.github.vdenisov.jdkswitcher.helper

import java.nio.file.Files

/**
 * Runs the JDK Switcher scripts against a throwaway home directory.
 * Both paths the scripts touch are injectable - the JDKs directory is resolved against user.home,
 * and the active symlink comes from config.properties - so a sandboxed run cannot reach the real
 * environment even if a script misbehaves.
 */
class ScriptSandbox {

    private static final List<String> SCRIPTS =
        ['common.groovy', 'jdk-update.groovy', 'jdks.groovy', 'jdk-init.groovy']

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
        this.activeJdk = new File(home, 'jdk')
        this.scriptDir = new File(home, 'scripts')

        jdksDir.mkdirs()
        scriptDir.mkdirs()
        SCRIPTS.each { new File(scriptDir, it).text = new File(it).text }
        writeConfig()
    }

    /**
     * Create an empty JDK installation directory named the way vendors name them
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
     * Run one of the scripts as a separate process, since every script ends in System.exit
     * @param script Script file name
     * @param args Command line arguments for the script
     * @param jdkHome JDK to run under, defaults to the first entry of JDK_HOMES
     *
     * @return map of exitCode, stdout and stderr
     */
    Map run(String script, List<String> args = [], String jdkHome = JDK_HOMES.first()) {
        def command = ["${jdkHome}\\bin\\java.exe".toString(),
                       '-cp', System.getProperty('script.runtime.classpath'),
                       "-Duser.home=${home.absolutePath}".toString(),
                       'groovy.ui.GroovyMain',
                       new File(scriptDir, script).absolutePath] + args

        def process = command.execute()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        return [exitCode: process.exitValue(), stdout: stdout.toString(), stderr: stderr.toString()]
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

    private void writeConfig() {
        // Written through Properties so that backslashes in the Windows path get escaped
        def config = new Properties()
        config.setProperty('jdks.base.dir', '.jdks')
        config.setProperty('jdks.symlink.path', activeJdk.absolutePath)
        new File(scriptDir, 'config.properties').withOutputStream { config.store(it, null) }
    }
}
