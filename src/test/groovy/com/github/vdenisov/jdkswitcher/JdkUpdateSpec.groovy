package com.github.vdenisov.jdkswitcher

import com.github.vdenisov.jdkswitcher.helper.ScriptSandbox
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tier 2 tests for jdk-update.groovy, run as a real process against a sandboxed home directory.
 * Skipped when this machine cannot create symlinks, which needs Developer Mode or an elevated shell.
 */
@Requires({ ScriptSandbox.symlinksAvailable() })
class JdkUpdateSpec extends Specification {

    @TempDir
    File home

    ScriptSandbox sandbox

    def setup() {
        sandbox = new ScriptSandbox(home)
    }

    def "creates a version symlink for every major version under JDK #jdkHome"() {
        given:
        ['temurin-11.0.32.1', 'temurin-17.0.16', 'corretto-21.0.12.1', 'temurin-25.0.4.1']
            .each { sandbox.createJdk(it) }

        when:
        def result = sandbox.run('jdk-update.groovy', [], jdkHome)

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '11')).endsWith('temurin-11.0.32.1')
        sandbox.targetOf(new File(sandbox.jdksDir, '17')).endsWith('temurin-17.0.16')
        sandbox.targetOf(new File(sandbox.jdksDir, '21')).endsWith('corretto-21.0.12.1')
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')

        where:
        jdkHome << ScriptSandbox.JDK_HOMES
    }

    def "repoints a symlink whose target was renamed by a JDK update"() {
        given: "a 25 symlink created against the pre-update directory"
        sandbox.createJdk('temurin-25.0.4')
        sandbox.run('jdk-update.groovy')

        and: "the vendor renames the installation, leaving the symlink dangling"
        new File(sandbox.jdksDir, 'temurin-25.0.4').renameTo(new File(sandbox.jdksDir, 'temurin-25.0.4.1'))

        expect: "the symlink is now broken, which File#exists cannot see"
        !new File(sandbox.jdksDir, '25').exists()

        when:
        def result = sandbox.run('jdk-update.groovy')

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')
        new File(sandbox.jdksDir, '25').exists()
    }

    def "picks the highest version within a major version"() {
        given:
        ['temurin-25.0.4', 'temurin-25.0.4.1', 'temurin-25.0.10'].each { sandbox.createJdk(it) }

        when:
        def result = sandbox.run('jdk-update.groovy')

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.10')
    }

    def "updates only the requested major version"() {
        given:
        ['temurin-17.0.16', 'temurin-25.0.4.1'].each { sandbox.createJdk(it) }

        when:
        def result = sandbox.run('jdk-update.groovy', ['25'])

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')
        !new File(sandbox.jdksDir, '17').exists()
    }

    def "accepts an explicit #description path"() {
        given:
        def jdk = sandbox.createJdk('temurin-25.0.4.1')

        when:
        def result = sandbox.run('jdk-update.groovy', ['25', absolute ? jdk.absolutePath : jdk.name])

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')

        where:
        description | absolute
        'absolute'  | true
        'relative'  | false
    }

    def "warns but still links when a relative path resolves outside the JDKs directory"() {
        given:
        new File(home, 'elsewhere/temurin-25.0.4.1').mkdirs()

        when:
        def result = sandbox.run('jdk-update.groovy', ['25', '../elsewhere/temurin-25.0.4.1'])

        then:
        result.exitCode == 0
        result.stderr.contains('outside .jdks directory')
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')
    }

    def "links an explicit path even when nothing is discoverable in the JDKs directory"() {
        given: "a target outside .jdks, so discovery finds nothing to enumerate"
        def outside = new File(home, 'elsewhere/temurin-25.0.4.1')
        outside.mkdirs()

        when:
        def result = sandbox.run('jdk-update.groovy', ['25', outside.absolutePath])

        then:
        result.exitCode == 0
        sandbox.targetOf(new File(sandbox.jdksDir, '25')).endsWith('temurin-25.0.4.1')
    }

    def "links a target whose path contains consecutive spaces"() {
        given: "runs of whitespace are what a splitting command line silently collapses"
        def spaced = new File(home, 'program  files/temurin-25.0.4.1')
        spaced.mkdirs()

        when:
        def result = sandbox.run('jdk-update.groovy', ['25', spaced.absolutePath])

        then: "the symlink resolves to the real directory, not to a single-spaced near miss"
        result.exitCode == 0
        new File(sandbox.jdksDir, '25').toPath().toRealPath() == spaced.toPath().toRealPath()
    }

    def "fails when the requested major version has no installation"() {
        given:
        sandbox.createJdk('temurin-17.0.16')

        when:
        def result = sandbox.run('jdk-update.groovy', ['25'])

        then:
        result.exitCode == 1
        result.stderr.contains('No JDK installations found for major version 25')
    }

    def "exits cleanly when there are no JDKs at all"() {
        when:
        def result = sandbox.run('jdk-update.groovy')

        then:
        result.exitCode == 0
        result.stdout.contains('No JDK installations found')
    }

    def "exits non-zero and names the versions it could not link"() {
        given: "a non-empty directory sitting where the 25 symlink has to go, so rmdir fails"
        sandbox.createJdk('temurin-25.0.4.1')
        sandbox.createJdk('temurin-17.0.16')
        new File(sandbox.jdksDir, '25/occupied').mkdirs()

        when:
        def result = sandbox.run('jdk-update.groovy')

        then:
        result.exitCode == 1
        result.stderr.contains('Failed to update symlinks for JDK 25')

        and: "the versions that could be linked still were"
        sandbox.targetOf(new File(sandbox.jdksDir, '17')).endsWith('temurin-17.0.16')
    }
}
