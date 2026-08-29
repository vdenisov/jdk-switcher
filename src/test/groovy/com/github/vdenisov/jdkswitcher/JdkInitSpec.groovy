package com.github.vdenisov.jdkswitcher

import com.github.vdenisov.jdkswitcher.helper.ScriptSandbox
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tier 3 tests for jdk-init.groovy, the only script that writes machine-scope PATH and JAVA_HOME.
 *
 * There is no way to inject those, so this runs on CI only, where the runner is a disposable
 * elevated VM. The CI guard is not a convenience - without it a local run rewrites the system PATH.
 */
@Requires({ env.CI && ScriptSandbox.symlinksAvailable() })
class JdkInitSpec extends Specification {

    @TempDir
    File home

    ScriptSandbox sandbox

    def setup() {
        sandbox = new ScriptSandbox(home)
        sandbox.createJdk('temurin-17.0.16', '17.0.16')
        sandbox.createJdk('temurin-25.0.4.1', '25.0.4.1')
    }

    def "sets up the environment end to end"() {
        when:
        def result = sandbox.run('jdk-init.groovy')

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

        and: "java resolves all the way through the two symlink hops"
        javaVersionThroughActiveJdk().contains('25.0.4.1')
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

    private String javaVersionThroughActiveJdk() {
        def process = ['cmd', '/c', new File(sandbox.activeJdk, 'bin/java.bat').absolutePath, '-version'].execute()
        def stdout = new StringWriter()
        process.waitForProcessOutput(stdout, new StringWriter())
        return stdout.toString()
    }
}
