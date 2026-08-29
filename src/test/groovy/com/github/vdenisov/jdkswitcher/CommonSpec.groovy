package com.github.vdenisov.jdkswitcher

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tier 1 tests for the pure logic in common.groovy: version ordering and JDK directory discovery.
 * Runs in-process against the evaluated script, so it needs no symlinks, no admin rights and no Docker.
 */
class CommonSpec extends Specification {

    @Shared
    def common = new GroovyShell().evaluate(new File('common.groovy'))

    @TempDir
    File jdksDir

    def "compareVersions orders #left against #right"() {
        expect:
        Integer.signum(common.compareVersions(left, right)) == expected

        where:
        left               | right              || expected
        [25, 0, 4]         | [25, 0, 4, 1]      || -1
        [25, 0, 4, 1]      | [25, 0, 4]         || 1
        [25, 0, 4]         | [25, 0, 10]        || -1
        [25, 0, 1000]      | [25, 1, 0]         || -1
        [11, 0, 32, 1]     | [11, 0, 9]         || 1
        [21]               | [21, 0, 1]         || -1
        [21]               | [21, 0, 0]         || 0
        [17, 0, 16]        | [17, 0, 16]        || 0
        [25]               | [11, 0, 32, 1]     || 1
    }

    def "findLatestJdk picks #expected out of #versions"() {
        expect:
        common.findLatestJdk(versions.collect { jdkEntry(it) }).version == expected

        where:
        versions                            || expected
        ['25.0.4', '25.0.4.1']              || '25.0.4.1'
        ['25.0.4.1', '25.0.4']              || '25.0.4.1'
        ['25.0.4', '25.0.10']               || '25.0.10'
        ['25.0.1000', '25.1.0']             || '25.1.0'
        ['11.0.32.1', '11.0.9']             || '11.0.32.1'
        ['21']                              || '21'
        ['21', '21.0.1']                    || '21.0.1'
        ['21.0.7', '21.0.7.6', '21.0.9']    || '21.0.9'
    }

    def "escapePowerShellLiteral turns #value into #expected"() {
        expect:
        common.escapePowerShellLiteral(value) == expected

        where:
        value                        || expected
        'C:\\jdk'                    || 'C:\\jdk'
        "C:\\Users\\O'Brien\\.jdks"  || "C:\\Users\\O''Brien\\.jdks"
        "''"                         || "''''"
        ''                           || ''
        null                         || ''
    }

    def "the escaped value survives a round trip through PowerShell"() {
        given: "a path with the character that would otherwise close the literal early"
        def value = "C:\\Users\\O'Brien\\.jdks;C:\\jdk\\bin"

        when: "it is embedded in a single-quoted literal the way jdk-init does"
        def script = "Write-Output '${common.escapePowerShellLiteral(value)}'"
        def process = ['powershell', '-NoProfile', '-Command', script].execute()
        def stdout = new StringWriter()
        process.waitForProcessOutput(stdout, new StringWriter())

        then:
        process.exitValue() == 0
        stdout.toString().trim() == value
    }

    def "discoverJdks groups installations by major version"() {
        given:
        ['temurin-11.0.32.1', 'temurin-17.0.16', 'corretto-21.0.12.1', 'temurin-25.0.4', 'temurin-25.0.4.1']
            .each { new File(jdksDir, it).mkdirs() }

        when:
        def jdksByMajor = common.discoverJdks(jdksDir)

        then:
        jdksByMajor.keySet().sort() == [11, 17, 21, 25]
        jdksByMajor[25]*.version.sort() == ['25.0.4', '25.0.4.1']
        jdksByMajor[21].first().vendor == 'corretto'
        jdksByMajor[11].first().versionParts == [11, 0, 32, 1]
        jdksByMajor[17].first().directory.name == 'temurin-17.0.16'
    }

    def "discoverJdks keeps the hyphenated part of a vendor name"() {
        given:
        new File(jdksDir, 'jbr-jcef-21.0.5').mkdirs()

        when:
        def jdksByMajor = common.discoverJdks(jdksDir)

        then:
        jdksByMajor[21].first().vendor == 'jbr-jcef'
        jdksByMajor[21].first().version == '21.0.5'
    }

    def "discoverJdks ignores #description"() {
        given:
        new File(jdksDir, 'temurin-25.0.4.1').mkdirs()
        create(entry)

        expect:
        common.discoverJdks(jdksDir).keySet() == [25] as Set

        where:
        entry                          | description
        '25'                           | 'the numeric version symlinks this tool creates'
        'temurin-snapshot'             | 'directories with no version part'
        'no-separator'                 | 'directories that do not match vendor-version'
        '.temurin-25.0.4.1.intellij'   | 'files left behind by IntelliJ'
    }

    def "discoverJdks returns an empty map for a directory with no JDKs"() {
        expect:
        common.discoverJdks(jdksDir).isEmpty()
    }

    private Map jdkEntry(String version) {
        return [version: version, versionParts: version.tokenize('.').collect { it.toInteger() }]
    }

    private void create(String name) {
        // IntelliJ's marker files must be skipped as files, everything else is a directory
        name.startsWith('.') ? new File(jdksDir, name).createNewFile() : new File(jdksDir, name).mkdirs()
    }
}
