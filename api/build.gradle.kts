plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
    `maven-publish`
}

dependencies {
    api(libs.kotlinxSerializationCore)
}

java {
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xno-source-debug-extension")
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "dreamdisplays-api"
            from(components["java"])
            pom {
                name.set("Dream Displays API")
                description.set("Public API for integrating with Dream Displays.")
            }
        }
    }
}
