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

    def "reports the version of a real JDK after switching to it"() {
        given: "an installation that genuinely runs, rather than a stub"
        sandbox.createRealJdk('temurin-25.0.4.1')
        sandbox.run('jdk-update.groovy')

        when:
        def result = sandbox.run('jdks.groovy', ['25'])

        then: "the banner came from java itself, through both symlink hops"
        result.exitCode == 0
        result.stdout =~ /(?m)^\s+\w*jdk version "\d+/
    }

    def "reports the version of a real JDK with no arguments"() {
        given:
        sandbox.createRealJdk('temurin-25.0.4.1')
        sandbox.run('jdk-update.groovy')
        sandbox.run('jdks.groovy', ['25'])

        when:
        def result = sandbox.run('jdks.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('Active JDK: temurin-25.0.4.1')
        result.stdout =~ /(?m)^\s+\w*jdk version "\d+/
    }

    def "warns when the target does not look like a JDK"() {
        given: "an installation with no bin directory at all"
        sandbox.createJdk('temurin-25.0.4.1')
        sandbox.run('jdk-update.groovy')

        when:
        def result = sandbox.run('jdks.groovy', ['25'])

        then: "the switch still succeeds, the user may have pointed it somewhere deliberately"
        result.exitCode == 0
        result.stderr.contains('does not look like a JDK')
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

        and: "only the newer 25 installation remains, so the version symlink is stale"
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

        and: "only the newer 25 installation remains, so the active symlink resolves through a broken link"
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

    def "rejects more than one argument"() {
        when:
        def result = sandbox.run('jdks.groovy', ['17', 'extra'])

        then: "usage goes to stderr, since it was provoked rather than asked for"
        result.exitCode == 1
        result.stderr.contains('expected at most one argument')
        result.stderr.contains('Usage: jdks')
    }

    def "prints usage on stdout for #flag"() {
        when:
        def result = sandbox.run('jdks.groovy', [flag])

        then:
        result.exitCode == 0
        result.stdout.contains('Usage: jdks')
        result.stdout.contains('jdks latest')

        and: "the other two commands are discoverable from here"
        result.stdout.contains('jdk-update')
        result.stdout.contains('jdk-init')

        where:
        // /? is not here on purpose: the Java launcher glob-expands "?" on Windows before the
        // script sees it, so the flag cannot be supported through this invocation path
        flag << ['--help', '-h', 'help', '--HELP']
    }

    def "with no arguments reports the active JDK and what is available"() {
        given:
        ['temurin-11.0.32.1', 'temurin-17.0.16', 'temurin-25.0.4.1'].each { sandbox.createJdk(it) }
        sandbox.run('jdk-update.groovy')
        sandbox.run('jdks.groovy', ['17'])

        when:
        def result = sandbox.run('jdks.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('Active JDK: temurin-17.0.16')

        and: "every version symlink is listed against the installation it resolves to"
        result.stdout.contains('11 -> temurin-11.0.32.1')
        result.stdout.contains('17 -> temurin-17.0.16  (active)')
        result.stdout.contains('25 -> temurin-25.0.4.1')

        and: "only the active one is marked"
        result.stdout.count('(active)') == 1

        and: "usage stays discoverable without printing the whole block every time"
        result.stdout.contains("'jdks --help' for all options")
        !result.stdout.contains('Usage: jdks')
    }

    def "with no arguments says so when nothing is active yet"() {
        given:
        sandbox.createJdk('temurin-25.0.4.1')
        sandbox.run('jdk-update.groovy')

        when:
        def result = sandbox.run('jdks.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('Active JDK: none')
        result.stdout.contains('25 -> temurin-25.0.4.1')
        !result.stdout.contains('(active)')
    }

    def "with no arguments points at jdk-update when there are no version symlinks"() {
        when:
        def result = sandbox.run('jdks.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains("run 'jdk-update' to create them")
    }

    def "with no arguments reports a dangling version symlink instead of hiding it"() {
        given:
        sandbox.createJdk('temurin-25.0.4')
        sandbox.run('jdk-update.groovy')
        new File(sandbox.jdksDir, 'temurin-25.0.4').renameTo(new File(sandbox.jdksDir, 'temurin-25.0.4.1'))

        when:
        def result = sandbox.run('jdks.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('25 -> dangling, run jdk-update')
    }
}
