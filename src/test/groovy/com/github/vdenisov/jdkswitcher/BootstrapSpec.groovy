package com.github.vdenisov.jdkswitcher

import com.github.vdenisov.jdkswitcher.helper.ScriptSandbox
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tests the JVM lookup in jdk-common.bat, which is what lets the scripts still run when the active
 * JDK symlink they manage is dangling. Needs no symlinks and no privileges: the lookup keys off
 * USERPROFILE, so the child process is simply given a different one.
 */
class BootstrapSpec extends Specification {

    @TempDir
    File home

    ScriptSandbox sandbox

    def setup() {
        sandbox = new ScriptSandbox(home)
    }

    def "falls back to an installed JDK when JAVA_HOME is #description"() {
        given:
        installJdk('temurin-25.0.4.1')

        when:
        def result = runBootstrap(javaHome)

        then:
        result.exitCode == 0
        result.stdout.contains('falling back to')
        resolvedJavaHome(result) == new File(home, '.jdks/temurin-25.0.4.1').absolutePath

        where:
        description                    | javaHome
        'pointing at a deleted target' | 'C:\\jdk-that-does-not-exist'
        'empty'                        | ''
    }

    def "leaves a working JAVA_HOME alone"() {
        given:
        def working = installJdk('temurin-17.0.16')
        installJdk('temurin-25.0.4.1')

        when:
        def result = runBootstrap(working.absolutePath)

        then:
        result.exitCode == 0
        !result.stdout.contains('falling back to')
        resolvedJavaHome(result) == working.absolutePath
    }

    def "skips a dangling version symlink rather than picking it"() {
        given: "a numeric entry with no bin\\java.exe, which is what a stale symlink looks like"
        new File(home, '.jdks/25').mkdirs()
        installJdk('temurin-25.0.4.1')

        when:
        def result = runBootstrap('C:\\jdk-that-does-not-exist')

        then:
        resolvedJavaHome(result) == new File(home, '.jdks/temurin-25.0.4.1').absolutePath
    }

    def "fails with an actionable message when no JDK can be found at all"() {
        when:
        def result = runBootstrap('C:\\jdk-that-does-not-exist')

        then:
        result.exitCode == 1
        result.stderr.contains('does not point at a usable JDK')
        result.stderr.contains('run jdk-update to repair')
    }

    private File installJdk(String name) {
        def jdk = new File(home, ".jdks/${name}")
        new File(jdk, 'bin').mkdirs()
        // Only its existence matters, jdk-common.bat probes for the file and never runs it
        new File(jdk, 'bin/java.exe').createNewFile()
        return jdk
    }

    /**
     * Runs jdk-common.bat through a probe that echoes the resulting JAVA_HOME, since the value is
     * only observable after the call returns.
     */
    private Map runBootstrap(String javaHome) {
        def scripts = new File(home, 'scripts')
        def probe = new File(scripts, 'probe.bat')
        probe.text = ['@echo off',
                      "call \"${new File(scripts, 'jdk-common.bat').absolutePath}\"",
                      'if errorlevel 1 exit /b 1',
                      'echo RESOLVED=%JAVA_HOME%'].join('\r\n') + '\r\n'

        def builder = new ProcessBuilder(['cmd', '/c', probe.absolutePath])
        builder.environment().put('USERPROFILE', home.absolutePath)
        builder.environment().put('JAVA_HOME', javaHome)

        def process = builder.start()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        return [exitCode: process.exitValue(), stdout: stdout.toString(), stderr: stderr.toString()]
    }

    private static String resolvedJavaHome(Map result) {
        return (result.stdout =~ /RESOLVED=(.*)/)[0][1].trim()
    }
}
