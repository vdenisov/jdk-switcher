package com.github.vdenisov.jdkswitcher

import com.github.vdenisov.jdkswitcher.helper.ScriptSandbox
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tier 3 tests for jdk-init.groovy, the only script that writes machine-scope PATH and JAVA_HOME.
 *
 * There is no way to inject those, so this runs only on a GitHub-hosted runner, which is thrown
 * away when the job ends. A plain CI check would not be enough: self-hosted runners, Jenkins
 * agents and anyone who exports CI=1 would all match, and this spec rewrites the machine
 * environment of whatever host it runs on. The values are snapshotted and restored regardless.
 */
@Requires({
    env.GITHUB_ACTIONS && env.RUNNER_ENVIRONMENT == 'github-hosted' && ScriptSandbox.symlinksAvailable()
})
class JdkInitSpec extends Specification {

    @Shared
    String originalJavaHome

    @Shared
    String originalPath

    @TempDir
    File home

    ScriptSandbox sandbox

    def setupSpec() {
        originalJavaHome = ScriptSandbox.machineEnv('JAVA_HOME')
        originalPath = ScriptSandbox.machineEnv('Path')
    }

    def cleanupSpec() {
        // The temp directories these point at are about to be deleted, so leaving them behind
        // would break the host even where the host is disposable
        ScriptSandbox.setMachineEnv('JAVA_HOME', originalJavaHome)
        ScriptSandbox.setMachineEnv('Path', originalPath)
    }

    def setup() {
        sandbox = new ScriptSandbox(home)
        sandbox.createJdk('temurin-17.0.16')
        // The newest is the one jdk-init will make active, so it is the one that has to run
        sandbox.createRealJdk('temurin-25.0.4.1')
    }

    def "sets up the environment end to end under JDK #jdkHome"() {
        when:
        def result = sandbox.run('jdk-init.groovy', [], jdkHome)

        then:
        result.exitCode == 0
        result.stdout.contains('Initialization Complete!')

        and: "the child scripts ran and created the version symlinks"
        sandbox.targetOf(new File(sandbox.jdksDir, '17')).endsWith('temurin-17.0.16')
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')

        and: "the active symlink points at the newest major version"
        sandbox.targetOf(sandbox.activeJdk).endsWith('25')

        and: "the machine environment points at the active symlink, not at a concrete JDK"
        ScriptSandbox.machineEnv('JAVA_HOME') == sandbox.activeJdk.absolutePath
        ScriptSandbox.machineEnv('Path').toLowerCase()
            .contains("${sandbox.activeJdk.absolutePath}\\bin".toLowerCase())

        and: "a real java runs all the way through the two symlink hops"
        javaVersionThroughActiveJdk() =~ /\w*jdk version "\d+/

        where:
        jdkHome << ScriptSandbox.JDK_HOMES
    }

    def "is idempotent when the environment is already set up"() {
        given:
        sandbox.run('jdk-init.groovy')

        when:
        def result = sandbox.run('jdk-init.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('JAVA_HOME already set correctly')
        result.stdout.contains('JDK bin path already in PATH')

        and: "the PATH entry was not added a second time"
        ScriptSandbox.machineEnv('Path').toLowerCase()
            .count("${sandbox.activeJdk.absolutePath}\\bin".toLowerCase()) == 1
    }

    /**
     * Captures both streams, because java writes its -version banner to stderr rather than stdout.
     */
    private String javaVersionThroughActiveJdk() {
        def java = new File(sandbox.activeJdk, 'bin/java.exe')
        def process = [java.absolutePath, '-version'].execute()
        def stdout = new StringWriter()
        def stderr = new StringWriter()
        process.waitForProcessOutput(stdout, stderr)

        assert process.exitValue() == 0

        return stdout.toString() + stderr.toString()
    }
}
