plugins {
    `java`
    id("com.gradleup.shadow") version "9.2.2"
}

group = "archive.chat"
version = project.property("plugin_version")!!.toString()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api")}")
    compileOnly("com.google.code.gson:gson:2.11.0")  // Paper provides Gson
    implementation("io.lettuce:lettuce-core:6.5.3.RELEASE")
}

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}

tasks {
    withType(JavaCompile::class).configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "api_version" to project.property("api_version")
        )
        filesMatching("paper-plugin.yml") {
            filteringCharset = "UTF-8"
            expand(props)
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier = ""
        relocate("io.lettuce", "archive.chat.libs.lettuce")
    }

    build {
        dependsOn(shadowJar)
    }
}
