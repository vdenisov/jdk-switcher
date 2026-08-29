package com.github.vdenisov.jdkswitcher

import com.github.vdenisov.jdkswitcher.helper.ScriptSandbox
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tier 2 tests for jdks.groovy, run as a real process against a sandboxed home directory.
 * Skipped when this machine cannot create symlinks, which needs Developer Mode or an elevated shell.
 */
@Requires({ ScriptSandbox.symlinksAvailable() })
class JdksSpec extends Specification {

    @TempDir
    File home

    ScriptSandbox sandbox

    def setup() {
        sandbox = new ScriptSandbox(home)
    }

    def "points the active symlink at the requested version under JDK #jdkHome"() {
        given:
        ['temurin-17.0.16', 'temurin-25.0.4.1'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy', [], jdkHome)

        when:
        def result = sandbox.run('jdks.groovy', ['17'], jdkHome)

        then:
        result.exitCode == 0
        sandbox.targetOf(sandbox.activeJdk).endsWith('17')

        where:
        jdkHome << ScriptSandbox.JDK_HOMES
    }

    def "latest selects the highest major version"() {
        given:
        ['temurin-11.0.32.1', 'temurin-17.0.16', 'temurin-25.0.4.1'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy')

        when:
        def result = sandbox.run('jdks.groovy', ['latest'])

        then:
        result.exitCode == 0
        result.stdout.contains('Selected latest JDK version: 25')
        sandbox.targetOf(sandbox.activeJdk).endsWith('25')
    }

    def "latest fails loudly rather than downgrading when the newest symlink is dangling"() {
        given:
        ['temurin-17.0.16', 'temurin-25.0.4'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy')

        and: "the 25 installation is renamed out from under its symlink"
        new File(sandbox.jdksDir, 'temurin-25.0.4').renameTo(new File(sandbox.jdksDir, 'temurin-25.0.4.1'))

        when:
        def result = sandbox.run('jdks.groovy', ['latest'])

        then: "it must not silently fall back to 17"
        result.exitCode == 1
        result.stderr.contains('symlink is dangling')
        sandbox.targetOf(sandbox.activeJdk) == null
    }

    def "replaces an existing active symlink"() {
        given:
        ['temurin-17.0.16', 'temurin-25.0.4.1'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy')
        sandbox.run('jdks.groovy', ['17'])

        when:
        def result = sandbox.run('jdks.groovy', ['25'])

        then:
        result.exitCode == 0
        sandbox.targetOf(sandbox.activeJdk).endsWith('25')
    }

    def "replaces an active symlink that is itself dangling"() {
        given:
        ['temurin-17.0.16', 'temurin-25.0.4'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy')
        sandbox.run('jdks.groovy', ['25'])

        and: "the 25 installation is renamed, so C:\\jdk now resolves through a broken link"
        new File(sandbox.jdksDir, 'temurin-25.0.4').renameTo(new File(sandbox.jdksDir, 'temurin-25.0.4.1'))

        when:
        def result = sandbox.run('jdks.groovy', ['17'])

        then:
        result.exitCode == 0
        sandbox.targetOf(sandbox.activeJdk).endsWith('17')
    }

    def "fails when the requested version has no symlink"() {
        given:
        sandbox.createJdk('temurin-17.0.16')
        sandbox.run('jdk-update.groovy')

        when:
        def result = sandbox.run('jdks.groovy', ['25'])

        then:
        result.exitCode == 1
        result.stderr.contains('Target JDK directory does not exist')
    }

    def "fails when no version symlinks exist at all"() {
        when:
        def result = sandbox.run('jdks.groovy', ['latest'])

        then:
        result.exitCode == 1
        result.stderr.contains('No JDK version symlinks found')
    }

    def "requires exactly one argument"() {
        when:
        def result = sandbox.run('jdks.groovy', args)

        then:
        result.exitCode == 1
        result.stderr.contains('Usage: jdks')

        where:
        args << [[], ['17', 'extra']]
    }
}
