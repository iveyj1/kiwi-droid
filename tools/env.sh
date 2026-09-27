#!/usr/bin/env bash
# Source this file; keeps Android/JDK changes local to the current shell.
export KIWI_TOOLS_HOME="${KIWI_TOOLS_HOME:-$HOME/.local/share/kiwi-droid}"
export JAVA_HOME="${JAVA_HOME:-$KIWI_TOOLS_HOME/toolchains/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$KIWI_TOOLS_HOME/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
