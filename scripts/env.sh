#!/usr/bin/env bash
# Source this before any Gradle/CMake command in a bare container:
#   source scripts/env.sh
# CI uses its own action-provided toolchain; this file only makes local/agent shells reproducible.
TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-/tmp/toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN_ROOT/jdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$TOOLCHAIN_ROOT/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-home}"
export LLAMA_DIR="${LLAMA_DIR:-$TOOLCHAIN_ROOT/llama.cpp}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$TOOLCHAIN_ROOT/gradle-8.11.1/bin:$PATH"
