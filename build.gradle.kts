plugins {
    java
}

group = "com.valleyrealm"
version = "0.1.0-alpha"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://repo.opencollab.dev/maven-releases") {
        mavenContent { releasesOnly() }
    }
    maven("https://repo.opencollab.dev/maven-snapshots") {
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Packet library — primary
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    // Packet library — fallback (kept as compileOnly, no runtime dependency)
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    // Floodgate API — soft dependency (all transitive deps excluded; bundled on server)
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT") {
        exclude(group = "org.geysermc.geyser", module = "common")
        exclude(group = "org.geysermc.cumulus", module = "cumulus")
        exclude(group = "org.geysermc.event", module = "events")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(26))
}

tasks {
    compileJava {
        options.release = 21
    }
}
