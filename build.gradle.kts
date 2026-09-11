plugins {
    java
    `maven-publish`
}

group = property("group").toString()
version = property("version").toString()

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
