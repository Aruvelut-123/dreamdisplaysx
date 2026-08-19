fun cargoExecutable(): String {
    val inHome = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    return if (inHome.canExecute()) inHome.absolutePath else "cargo"
}

tasks.register<Exec>("buildHostNatives") {
    group = "native"
    description = "Builds the host Rust native libraries (release) into native/target/release for the client to bundle."
    val dir = projectDir
    val cargo = cargoExecutable()

    // Gradle up-to-date: inputs = Rust sources + lockfile, outputs = release artifacts
    inputs.files(
        fileTree(dir) { include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock") },
        fileTree(file("$dir/lav")) { include("**/*.rs", "**/Cargo.toml") },
        fileTree(file("$dir/logging")) { include("**/*.rs", "**/Cargo.toml") },
    )
    outputs.dir(file("$dir/target/release"))

    workingDir = dir
    commandLine(cargo, "build", "--release")
    doFirst { logger.lifecycle("Building host natives with '$cargo' in $dir...") }
}

tasks.register<Exec>("testHostNatives") {
    group = "native"
    description = "Runs the Rust native test suite (cargo test)."
    workingDir = projectDir
    commandLine(cargoExecutable(), "test")
}
