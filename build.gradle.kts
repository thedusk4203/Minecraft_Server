plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.thedusk.minecraft"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")

    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.flywaydb:flyway-core:13.11.1")
    implementation("org.flywaydb:flyway-database-postgresql:13.11.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("minecraft-server-core")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
