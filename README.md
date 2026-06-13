fabric-loader
===========

The loader for mods under Fabric. It provides mod loading facilities and useful abstractions for other mods to use.

## Build requirements

Fabric Loader must be built with a Java 21+ Gradle runtime.

The Spiral Knights provider also bundles Java 25 `java.compiler` API classes so
Mixin can run on the game's stripped Java 25 runtime. The build gets these
classes from a Java 25 toolchain. Gradle can auto-download that toolchain through
Foojay when network access is available; offline CI should install JDK 25 ahead
of time or point Gradle at it with `org.gradle.java.installations.paths`.

In short: CI may run Gradle on Java 21 or newer, but the build must be able to
resolve a full JDK 25 with `bin/jimage` and `lib/modules`.

## License

Licensed under the Apache License 2.0.
