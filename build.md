# Build

## Requirements

- macOS, Linux, or Windows with a shell capable of running the Gradle wrapper.
- Java 21 or newer to run Gradle.
- A full JDK 25 toolchain with `bin/jimage` and `lib/modules`. Gradle can
  auto-download it through Foojay when network access is available.
- Network access for the first build so Gradle can download its wrapper distribution and dependencies.

## Recommended setup

The repository pins `openjdk-25.0.2` in `.tool-versions`. If you use `asdf` and
`direnv`, run the following from the repository root:

```sh
asdf install
direnv allow
```

This setup is convenient but not required. You can instead use any Java 21+
Gradle runtime as long as Gradle can resolve a full JDK 25 toolchain.

## Build

From the repository root:

```sh
./gradlew build
```

This runs the checks and tests and creates the final, ProGuard-processed loader
jar. For a local build, the main artifact is:

```text
build/libs/fabric-loader-0.19.3+local.jar
```

Release builds omit the `+local` version suffix. Additional fat, sources,
Javadoc, and installer metadata artifacts are written to `build/libs/`.

To create only the final loader jar without running the full build lifecycle:

```sh
./gradlew finalJar
```

Do not use `java -jar` to start Spiral Knights: the jar manifest points to
Fabric's server launcher, not the Spiral Knights bootstrap entry point.

## Other useful commands

```sh
# Run unit tests
./gradlew test

# Run formatting, Checkstyle, and tests without packaging
./gradlew check
```
