plugins {
    id("base-conventions")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.openrs2.org/repository/openrs2-snapshots")
    maven("https://raw.githubusercontent.com/OpenRune/hosting/master")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(projects.tools.shopMaker)

    intellijPlatform {
        intellijIdea("2024.3.6")
    }
}

intellijPlatform {
    buildSearchableOptions = false
}
