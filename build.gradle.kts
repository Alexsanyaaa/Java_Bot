plugins {
    java
    id("org.graalvm.buildtools.native") version "0.11.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Plain Java since 2026-11-27: no Spring, so only JSON, the database driver and a tiny logger.
dependencies {
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    implementation("com.h2database:h2:2.4.240")
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// One runnable JAR with the libraries inside, as Spring Boot's bootJar was: build/libs/java-mentor-bot.jar.
tasks.jar {
    archiveFileName = "java-mentor-bot.jar"
    manifest {
        attributes("Main-Class" to "mentor.MentorApplication", "Multi-Release" to "true")
    }
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "module-info.class",
        "META-INF/versions/*/module-info.class")
}

// The old command `./gradlew test bootJar` keeps working.
tasks.register("bootJar") {
    dependsOn(tasks.jar)
}

// Native image (GraalVM 21, built on Linux only; see docs/09-native-image.md).
val reflectionConfig = layout.buildDirectory.dir("native-config")
val nativeReflectionConfig = tasks.register("nativeReflectionConfig") {
    description = "Lets Jackson reach every class of the bot by reflection in the native image."
    val classes = sourceSets.main.get().output.classesDirs
    inputs.files(classes)
    outputs.dir(reflectionConfig)
    doLast {
        val names = classes.files.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }.toList()
        }.filter { it.startsWith("mentor.") }.sorted()
        val entries = names.joinToString(",\n") { name ->
            "  {\"name\": \"$name\", \"allDeclaredConstructors\": true, \"allPublicConstructors\": true, " +
                "\"allDeclaredMethods\": true, \"allPublicMethods\": true, \"allDeclaredFields\": true, " +
                "\"allPublicFields\": true}"
        }
        val dir = reflectionConfig.get().asFile
        dir.mkdirs()
        dir.resolve("reflect-config.json").writeText("[\n$entries\n]\n")
        dir.resolve("resource-config.json").writeText(
            "{\"resources\": {\"includes\": [{\"pattern\": \"simplelogger.properties\"}]}}\n")
    }
}

graalvmNative {
    toolchainDetection = false // use the GraalVM in JAVA_HOME
    metadataRepository {
        enabled = true
    }
    // `./gradlew -Pagent test` records what the tests reach by reflection; `./gradlew metadataCopy` saves it here.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image/mentor/java-mentor-bot")
            mergeWithExisting = true
        }
    }
    binaries {
        named("main") {
            imageName = "java-mentor-bot"
            mainClass = "mentor.MentorApplication"
        }
        all {
            configurationFileDirectories.from(reflectionConfig)
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers", // SIGTERM from systemd runs the shutdown hook (inFlightUpdate, DB close)
                "-H:+UnlockExperimentalVMOptions",
                "-march=compatibility", // built on a desktop CPU, runs on the server's
                "--enable-url-protocols=http,https",
                "-H:+ReportExceptionStackTraces",
            )
        }
    }
}

tasks.named("nativeCompile") { dependsOn(nativeReflectionConfig) }
tasks.named("nativeTestCompile") { dependsOn(nativeReflectionConfig) }
