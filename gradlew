#!/bin/sh

# Gradle wrapper start up script
APP_NAME="Gradle"
APP_HOME=$( cd -P "${0%/*}" > /dev/null && pwd ) || exit

# Determine the Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    fi
fi

exec "$JAVACMD" \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_NAME" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
