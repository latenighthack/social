#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
gradle="${SOCIAL_GRADLE_WRAPPER:-./gradlew}"
options=(--no-daemon --console=plain)
if [[ -n "${FH_WORKSPACE:-}" ]]; then options+=("-PfhWorkspace=$FH_WORKSPACE"); fi
if [[ -n "${FH_MAVEN_REPOSITORY:-}" ]]; then options+=("-Dmaven.repo.local=$FH_MAVEN_REPOSITORY"); fi
"$gradle" detekt "${options[@]}"
"$gradle" jvmTest :login-core-service:test :login-apple-service:test :login-google-service:test :login-email-service:test :login-phone-service:test :rooms-service:test :remote-content-service:test :messages-view-android:testDebugUnitTest "${options[@]}"
"$gradle" jsNodeTest compileKotlinJs compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosSimulatorArm64 iosSimulatorArm64Test "${options[@]}"
