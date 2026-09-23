# Desktop library notices

The desktop runtime includes the following component families. Platform-specific
dependency lockfiles under `app/gradle/dependency-locks/` record exact module
coordinates. Multiplatform metadata modules can redirect to JVM artifacts;
these are not additional copies of the same library in the application.

| Component family | Resolved version | License |
| --- | --- | --- |
| JetBrains Compose Multiplatform (desktop, animation, foundation, material, runtime, UI) | 1.12.1 | Apache-2.0 |
| AndroidX Compose runtime | 1.12.1 | Apache-2.0 |
| AndroidX Lifecycle | 2.11.0 | Apache-2.0 |
| JetBrains AndroidX Lifecycle adapters | 2.9.6 | Apache-2.0 |
| AndroidX SavedState / JetBrains adapters | 1.4.0 / 1.3.6 | Apache-2.0 |
| AndroidX NavigationEvent / JetBrains adapters | 1.1.1 / 1.1.0 | Apache-2.0 |
| AndroidX annotation / collection / arch-core | 1.9.1 / 1.5.0 / 2.2.0 | Apache-2.0 |
| Kotlin coroutines / atomicfu | 1.9.0 / 0.28.0 | Apache-2.0 |
| JetBrains annotations | 23.0.0 | Apache-2.0 |
| JSpecify annotations | 1.0.0 | Apache-2.0 |
| JetBrains Runtime API (`jbr-api`, not a bundled JDK) | 1.9.0 | Apache-2.0 |
| Skiko JVM bindings and platform runtime | 0.150.1 | Apache-2.0; native Skia and dependencies retain their own licenses |
| Skia graphics engine | m150-1f14f1166a | BSD-3-Clause, with separately licensed third-party components |

Kotlin standard library, serialization, and cryptographic library notices are
listed in the root `THIRD-PARTY-NOTICES`. The full Apache license is in
[Apache-2.0.txt](Apache-2.0.txt).

License declarations were checked in the published Maven POMs for the resolved
JVM modules: [Maven Central](https://repo.maven.apache.org/maven2/) and
[Google Maven](https://dl.google.com/dl/android/maven2/).
Upstream projects: [Compose](https://github.com/JetBrains/compose-multiplatform),
[AndroidX](https://android.googlesource.com/platform/frameworks/support/),
[Skiko](https://github.com/JetBrains/skiko/tree/v0.150.1).

## Skiko notice

From [Skiko 0.150.1 NOTICE](https://github.com/JetBrains/skiko/blob/v0.150.1/NOTICE):

```text
This project contains code adapted from "The Android Open Source Project" licensed under the Apache License, Version 2.0 (the "License"):

https://android.googlesource.com/platform/frameworks/base
```

## Skia notice

The Skia revision is pinned by
[Skiko's build properties](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/gradle.properties).
The following is the [license at that revision](https://github.com/JetBrains/skia/blob/m150-1f14f1166a/LICENSE):

```text
Copyright (c) 2011 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Native distribution coverage

The inspected `skiko-awt-runtime-windows-x64:0.150.1` JAR contains no aggregate
LICENSE or NOTICE file. The notices above cover Skiko and Skia themselves;
they are not a complete notice bundle for statically linked native codecs,
font libraries, and Unicode data. Before distributing native installers, the
exact platform binaries must be inventoried and their component notices
included. This also applies to any JDK runtime image included by packaging.
The reviewed per-platform inventories in [native](native/) cover this.

Native packaging now enforces this restriction with the
`checkNativeDistributionLicenses` Gradle task. It requires reviewed per-platform
notices bound to hashes of every resolved runtime artifact and the exact bundled
JDK legal files/version. Missing or stale records fail the build before native
image or installer creation; there is no bypass property. See
[Native packaging](../docs/PACKAGING.md) for the inventory format and workflow.

The upstream [Skiko v0.150.1 tree](https://github.com/JetBrains/skiko/tree/v0.150.1)
provides its own LICENSE and NOTICE. The
[pinned Skia tree](https://github.com/JetBrains/skia/tree/m150-1f14f1166a)
also contains separately licensed third-party directories and references external
dependencies. Those source-tree files alone do not establish which components
are linked into every prebuilt Skiko runtime. No complete per-binary native
inventory or bundled-JDK redistribution review is claimed here.

Verified source notices for the pinned native dependency revisions, checksummed
platform JAR contents, and Temurin source provenance are now collected in
[Native component provenance](native-evidence/README.md). That evidence identifies
DNG SDK code in the Linux and macOS binaries; Skia's BSD license does not
establish the terms of that separate component. The reviewed per-platform
notices, including the DNG SDK terms, are in [native](native/).
