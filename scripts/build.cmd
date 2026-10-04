@echo off
REM ---------------------------------------------------------------------------
REM Convenience wrapper for the Maven build.
REM
REM Maven and a JDK are not on PATH on every machine, so this script pins
REM JAVA_HOME and delegates to a project-local Maven install (see README).
REM Usage:  scripts\build.cmd [maven goals, e.g. "test" or "package -DskipTests"]
REM ---------------------------------------------------------------------------
setlocal

if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Java\jdk-21

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [build] JAVA_HOME not found at "%JAVA_HOME%".
  echo [build] Set JAVA_HOME to a JDK 21 installation and retry.
  exit /b 1
)

set MVN_CMD=mvnw.cmd
if not exist "%MVN_CMD%" set MVN_CMD=mvn

echo [build] JAVA_HOME=%JAVA_HOME%
echo [build] using %MVN_CMD%

call %MVN_CMD% %*
exit /b %ERRORLEVEL%
