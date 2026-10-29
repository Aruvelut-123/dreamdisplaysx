plugins {
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.serialization-conventions")
    `maven-publish`
}

dependencies {
    api(libs.kotlinxSerializationCore)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "dreamdisplayx-api"
            from(components["java"])
            pom {
                name.set("Dream Displays API")
                description.set("Public API for integrating with Dream Displays.")
            }
        }
    }
}
