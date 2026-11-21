---
okf_version: "0.2"
type: Runbook
title: "Android CLI build setup in the assistant environment"
description: "How ParamAuto gets built in the assistant environment: hand-assembled SDK, Temurin JDK 17, and an in-process Gradle launch (the sandbox intercepts daemon sockets and the proxy resets Java TLS). First successful build 2026-10-30."
tags: [android-cli, build, muse, permissions]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# Android CLI build setup

## Goal
Build ParamAuto in the assistant's environment (compile-verify commits before
pushing) instead of shipping uncompiled Kotlin for Param to compile in Android Studio.

## State (2026-10-27)
- **Android CLI installed**: v1.1.16717390 via the official `dl.google.com/android/cli/latest/linux_x86_64/install.sh` (installs to `~/.local/bin/android`; SDK path `~/Android/Sdk`). `android init` ran (agent skill dirs created).
- **Sources staged**: full ParamAuto tree at commit `a4119ea4c` in `~/workspace/ppa_build/` (77 files incl. Gradle wrapper and launcher icons), ready to build.
- **Blocked**: `android update` (and any Gradle dependency download) fails — the environment denies direct network connections from assistant-run programs: *"Other TCP connections is turned off for this assistant. To allow it, ask the user to open Muse settings → Permissions → Direct network protocols and switch other_tcp from Deny to Ask."*
- Param searched the app and **could not find that setting**. A feedback report was filed with the Muse team (2026-10-27, broken-behavior / network-permissions-setting, delivery confirmed): *"A blocking error points to an Other TCP connections permission setting the user cannot find anywhere in the app; please surface it in the UI or offer it in the approval prompt."* Feedback notes are one-way — no ticket, no reply.
- Workaround note: `curl` downloads work in this environment; the block hits Java/JVM networking (the CLI and Gradle), so pre-downloading with curl does not unblock a Gradle build.

## Next step when unblocked
1. `android update`, accept SDK licenses, install platform + build-tools for the project (targetSdk 34).
2. `cd ~/workspace/ppa_build && android build` (or `./gradlew assembleDebug`).
3. Fix any compile errors from the three uncompiled 2026-10-27 commits; push fixes via the normal approval flow.

## Related
- [build-history.md](build-history.md)

## Resolution (2026-10-30) — builds work

Param switched Muse's "Other TCP connections" permission to Ask; after that,
**direct** connections from JVM processes work. Three environment facts shape the recipe:

1. **Never route Java through the egress proxy** — the proxy resets Java TLS tunnels
   (curl through it is fine; JSSE/Apache-HC CONNECTs get RST). No proxy props for JVM/Gradle.
2. **Cross-session loopback TCP is intercepted** (proven with a two-JVM socket test: the
   client "connects", the server never accepts, the client reads EOF). A Gradle daemon is
   therefore unreachable — builds must run **in-process**.
3. The Android CLI's own updater still cannot reach the network (native stack), and its
   bundled JRE 25 has no `bin/java`. The CLI is not used for building.

Working setup:
- **JDK**: Temurin 17.0.21 at `~/tools/jdk-17.0.21+9` (curl download from Adoptium).
- **SDK**: hand-assembled at `~/Android/Sdk` from dl.google.com zips fetched with curl —
  `platforms/android-34` (platform-34-ext7_r03), `build-tools/34.0.0` (zip root is
  `android-14`), `platform-tools` r37, plus `licenses/android-sdk-license` written manually.
- **Gradle**: 9.7.1 unpacked at `~/tools/gradle-9.7.1` (curl; the wrapper's own JVM
  download fails). AGP 8.5.2 runs fine on it.
- **Launch** (from the project dir, `ANDROID_HOME=~/Android/Sdk`, no proxy env):
  `java -Xmx2048m -javaagent:~/tools/gradle-9.7.1/lib/agents/gradle-instrumentation-agent-9.7.1.jar -cp ~/tools/gradle-9.7.1/lib/gradle-launcher-9.7.1.jar org.gradle.launcher.GradleMain assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process`
  The client JVM opts must match `org.gradle.jvmargs` exactly and the instrumentation
  agent must be attached, or Gradle forks a single-use daemon and dies on the greeting.
- `local.properties` with `sdk.dir=/home/hatch/Android/Sdk` lives in the staging copy only.

First success: 2026-10-30, current main (2a1962ca) → `app-debug.apk` (56 MB) in
`~/workspace/ppa_build/app/build/outputs/apk/debug/`, warnings only.
The build also confirmed the October uncompiled commits had a real bug — `/api/*` inside
KDoc comments (Kotlin block comments nest) in GalaxyWebActivity.kt and
StarPilotBleBridge.kt — already fixed on main (`/api/...`).
