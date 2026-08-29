@echo off
rem Locate a usable JVM before handing over to Groovy.
rem
rem Groovy needs a JDK, and JAVA_HOME normally points at the active JDK symlink - which is the very
rem thing these scripts exist to repair. When a JDK update leaves that link dangling, Groovy cannot
rem start and jdk-update cannot be run to fix it. So fall back to the first real JDK found under the
rem JDKs directory, which is enough to get the repair done.
rem
rem The fallback assumes the default jdks.base.dir; a customised one gets the error below instead.

if exist "%JAVA_HOME%\bin\java.exe" exit /b 0

for /d %%d in ("%USERPROFILE%\.jdks\*") do (
    if not defined JDK_FALLBACK if exist "%%d\bin\java.exe" set "JDK_FALLBACK=%%d"
)

if not defined JDK_FALLBACK (
    echo ERROR: JAVA_HOME does not point at a usable JDK, and none was found under "%USERPROFILE%\.jdks".>&2
    echo Set JAVA_HOME to any installed JDK and run jdk-update to repair the symlinks.>&2
    exit /b 1
)

set "JAVA_HOME=%JDK_FALLBACK%"
set "JDK_FALLBACK="
echo Note: JAVA_HOME was unusable, falling back to %JAVA_HOME% to run this script.
exit /b 0
