plugins {
    java
    jacoco
}

group = "dev.mcpl"
version = "0.2.0"

// Runtime libraries are not shaded into the jar. Paper downloads them from Maven Central
// on startup (see `libraries` in plugin.yml, which is filled in from these values).
val hikariVersion = "7.1.0"
val sqliteVersion = "3.53.4.0"
val postgresVersion = "42.7.14"
val runtimeLibraries = listOf(
    "com.zaxxer:HikariCP:$hikariVersion",
    "org.xerial:sqlite-jdbc:$sqliteVersion",
    "org.postgresql:postgresql:$postgresVersion",
)

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.194-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")

    testImplementation("io.papermc.paper:paper-api:26.3.build.194-stable")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    runtimeLibraries.forEach { testImplementation(it) }
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "libraries" to runtimeLibraries.joinToString("\n") { "  - $it" },
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.test {
    useJUnitPlatform()
    // Load Mockito as an agent instead of attaching it at runtime (required on recent JDKs).
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.singleFile}") })
    // sqlite-jdbc loads a native library; allow it without a warning.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Silences the "Sharing is only supported for boot loader classes" note caused by the agents.
    jvmArgs("-Xshare:off")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// `./gradlew check` (and therefore `build`) fails if any line or branch is not covered.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
