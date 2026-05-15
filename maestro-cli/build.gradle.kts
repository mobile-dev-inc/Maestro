import org.jreleaser.model.Active.ALWAYS
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jreleaser.model.Stereotype
import org.gradle.language.jvm.tasks.ProcessResources
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.shadow)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.mobile"

val CLI_VERSION: String by project

application {
    applicationName = "maestro"
    mainClass.set("maestro.cli.AppKt")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "maestro.cli.AppKt"
    }
    // Include the driver source directly
    from("../maestro-ios-xctest-runner") {
        into("driver/ios")
        include(
            "maestro-driver-ios/**",
            "maestro-driver-iosUITests/**",
            "maestro-driver-ios.xcodeproj/**",
        )
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir
}

/** The source that was used to create JvmVersion is here. It was compiled with JDK 1.1
 * on a Windows 32 bit machine using https://www.oracle.com/java/technologies/java-archive-downloads-javase11-downloads.html
 * with a META-INF/MANIFEST.MF of Main-Class: JvmVersion for the jvm-version.jar
 *
 * import java.util.StringTokenizer;
 *
 * class JvmVersion {
 *     public static void main(String[] args) {
 *         try {
 *             String javaVersion = System.getProperty("java.version");
 * 			StringTokenizer tokenizer = new StringTokenizer(javaVersion, ".");
 * 			String[] split = new String[tokenizer.countTokens()];
 * 			int count = 0;
 * 			while (tokenizer.hasMoreTokens()) {
 * 				split[count] = tokenizer.nextToken();
 * 				count++;
 * 			}
 *             if (javaVersion.startsWith("1.")) {
 *                 String version = split[1];
 *                 if (Integer.parseInt(version) >= 1 && Integer.parseInt(version) <= 8) {
 *                     System.out.println(version);
 *                     System.exit(0);
 *                 } else {
 * 					String base = "Expected a JVM version of 1.0 through to 1.8 for legacy JVM versioning. Instead got ";
 *                     String output = base.concat(version);
 *                     System.out.println(output);
 *                     System.exit(1);
 *                 }
 *             } else {
 *                 String version = split[0];
 *                 if (Integer.parseInt(version) >= 9) {
 *                     System.out.println(version);
 *                     System.exit(0);
 *                 } else {
 * 					String base = "Expected a JVM version of 9 or greater for new JVM versioning. Instead got ";
 * 					String output = base.concat(version);
 * 					System.out.println(output);
 *                     System.exit(1);
 *                 }
 *             }
 *         } catch (Exception e) {
 *             System.err.println(e.getMessage());
 *             System.exit(1);
 *         }
 *     }
 * }
 *
 */

fun windowsMinimumJavaText(minimumJavaVersion: String): String = """
set JAVA_VERSION=0
for /f "tokens=*" %%g in ('cmd /c ""%JAVA_EXE%" -classpath "%APP_HOME%\bin\*" JvmVersion"') do (
  set JAVA_VERSION=%%g
)

if %JAVA_VERSION% LSS $minimumJavaVersion (
  echo.
  echo ERROR: Java $minimumJavaVersion or higher is required.
  echo.
  echo Please update Java, then try again.
  echo To check your Java version, run: java -version
  echo.
  echo See https://maestro.dev/blog/introducing-maestro-2-0-0 for more details.
  goto fail
)
""".trimIndent().replace("\n", "\r\n")

fun unixMinimumJavaText(minimumJavaVersion: String): String = """
JAVA_VERSION=$( "${'$'}JAVACMD" -classpath "${'$'}APP_HOME"/bin/*.jar JvmVersion )
if [ "${'$'}JAVA_VERSION" -lt $minimumJavaVersion ]; then
  die "ERROR: Java $minimumJavaVersion or higher is required.

Please update Java, then try again.
To check your Java version, run: java -version

See https://maestro.dev/blog/introducing-maestro-2-0-0 for more details."
fi
""".trimIndent()

tasks.named<CreateStartScripts>("startScripts") {
    doNotTrackState("classpath uses a JVM wildcard glob that cannot be statted on Windows")
    classpath = files("${layout.buildDirectory.get().asFile}/libs/*")
    doLast {
        val minimumJavaVersion = "17"
        val unixExec = "exec \"\$JAVACMD\" \"$@\""

        val currentUnix = unixScript.readText()
        val replacedUnix = currentUnix.replaceFirst(unixExec,
            unixMinimumJavaText(minimumJavaVersion) + "\n\n" + unixExec)
        unixScript.writeText(replacedUnix)

        val currentWindows = windowsScript.readText()
        val windowsExec = "@rem Execute maestro"
        val replacedWindows = currentWindows.replaceFirst(windowsExec,
            windowsMinimumJavaText(minimumJavaVersion) + "\r\n\r\n" + windowsExec)
        windowsScript.writeText(replacedWindows)

        val path = project.projectDir.toPath().resolve("jvm-version.jar")

        copy {
            from(path)
            into(outputDir)
        }
    }
}

dependencies {
    implementation(project(path = ":maestro-utils"))
    annotationProcessor(libs.picocli.codegen)

    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-client"))
    implementation(project(":maestro-ios"))
    implementation(project(":maestro-ios-driver"))
    implementation(project(":maestro-studio:server"))
    implementation(libs.apk.parser)
    implementation(libs.dd.plist)
    implementation(libs.posthog)
    implementation(libs.dadb)
    implementation(libs.picocli)
    implementation(libs.jackson.core.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.dataformat.xml)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jansi)
    implementation(libs.jcodec)
    implementation(libs.jcodec.awt)
    implementation(libs.square.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.jarchivelib)
    implementation(libs.commons.codec)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.html)
    implementation(libs.skiko.macos.arm64)
    implementation(libs.skiko.macos.x64)
    implementation(libs.skiko.linux.arm64)
    implementation(libs.skiko.linux.x64)
    implementation(libs.skiko.windows.arm64)
    implementation(libs.skiko.windows.x64)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.2.0")
    implementation(libs.mcp.kotlin.sdk) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
        exclude(group = "io.ktor")
    }
    implementation(libs.logging.sl4j)
    implementation(libs.logging.api)
    implementation(libs.logging.layout.template)
    implementation(libs.log4j.core)
    implementation(libs.mordant)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.google.truth)
    testImplementation(libs.system.stubs.jupiter)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val mcpVisualizerDir = layout.projectDirectory.dir("mcp-visualizer")
val simulatorServerSha = libs.versions.simulatorServer.get()
val simulatorServerGeneratedDir = layout.buildDirectory.dir("generated/simulator-server")

val downloadSimulatorServer = tasks.register("downloadSimulatorServer") {
    val sha = simulatorServerSha
    val outDir = simulatorServerGeneratedDir.get().asFile
    inputs.property("sha", sha)
    outputs.dir(outDir)

    doLast {
        val tag = "simulator-server-$sha"
        val baseUrl = "https://github.com/mobile-dev-inc/simulator-server-releases/releases/download/$tag"
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        fun download(assetName: String): ByteArray {
            val req = HttpRequest.newBuilder(URI.create("$baseUrl/$assetName"))
                .header("User-Agent", "maestro-cli-build")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            check(resp.statusCode() == 200) {
                "Failed to download $assetName from simulator-server-releases (HTTP ${resp.statusCode()}). " +
                    "Check that release $tag exists with the expected assets."
            }
            return resp.body()
        }

        val sums = String(download("SHA256SUMS"), Charsets.UTF_8)
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) parts[1] to parts[0].lowercase() else null
            }
            .toMap()

        val platforms = listOf(
            Triple("darwin", "simulator-server-$sha-darwin-universal.tar.gz", "targz"),
            Triple("linux", "simulator-server-$sha-linux-x86_64.tar.gz", "targz"),
            Triple("windows", "simulator-server-$sha-windows-x86_64.zip", "zip"),
        )

        outDir.deleteRecursively()
        val depsRoot = outDir.resolve("deps/simulator-server").apply { mkdirs() }

        platforms.forEach { (platform, assetName, kind) ->
            val expected = sums[assetName]
                ?: error("SHA256SUMS in release $tag has no entry for $assetName")
            val bytes = download(assetName)
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) {
                "Checksum mismatch for $assetName: expected $expected, got $actual"
            }

            val archive = outDir.resolve(assetName).apply { parentFile.mkdirs() }
            archive.writeBytes(bytes)

            val platformDir = depsRoot.resolve(platform).apply { mkdirs() }
            copy {
                from(if (kind == "zip") zipTree(archive) else tarTree(resources.gzip(archive)))
                into(platformDir)
            }
            archive.delete()
        }

        outDir.resolve("simulator-server-version.txt").writeText(sha)
    }
}

val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
val npmCommand = if (isWindows) "npm.cmd" else "npm"

tasks.register<Exec>("installMcpVisualizerDeps") {
    workingDir = mcpVisualizerDir.asFile
    inputs.file(mcpVisualizerDir.file("package.json"))
    inputs.file(mcpVisualizerDir.file("package-lock.json"))
        .optional()
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(mcpVisualizerDir.dir("node_modules"))
    commandLine(npmCommand, "install")
}

tasks.register<Exec>("buildMcpVisualizer") {
    workingDir = mcpVisualizerDir.asFile
    val inputFiles = fileTree(mcpVisualizerDir) {
        exclude("build", "node_modules")
    }
    inputs.files(inputFiles)
    outputs.file(mcpVisualizerDir.file("build/raw/index.html"))
    dependsOn("installMcpVisualizerDeps")
    commandLine(npmCommand, "run", "build")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("buildMcpVisualizer")
    dependsOn(downloadSimulatorServer)
    from(mcpVisualizerDir.dir("build/raw")) {
        into("mcp-visualizer")
    }
    from(simulatorServerGeneratedDir) {
        into("")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=17")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.create("createProperties") {
    dependsOn("processResources")

    doLast {
        File("$buildDir/resources/main/version.properties").writer().use { w ->
            val p = Properties()
            p["version"] = CLI_VERSION
            p.store(w, null)
        }
    }
}

tasks.register<Copy>("createTestResources") {
    from("../maestro-ios-xctest-runner") {
        into("driver/ios")
        include(
            "maestro-driver-ios/**",
            "maestro-driver-iosUITests/**",
            "maestro-driver-ios.xcodeproj/**"
        )
    }
    into(layout.buildDirectory.dir("resources/test"))
}

tasks.named("classes") {
    dependsOn("createTestResources")
    dependsOn("createProperties")
}

tasks.named<Zip>("distZip") {
    archiveFileName.set("maestro.zip")
}

tasks.named<Tar>("distTar") {
    archiveFileName.set("maestro.tar")
}

tasks.shadowJar {
    setProperty("zip64", true)
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

jreleaser {
    version = CLI_VERSION
    gitRootSearch.set(true)

    project {
        name.set("Maestro CLI")
        description.set("The easiest way to automate UI testing for your mobile app")
        links {
            homepage.set("https://maestro.mobile.dev")
            bugTracker.set("https://github.com/mobile-dev-inc/maestro/issues")
        }
        authors.set(listOf("Dmitry Zaytsev", "Amanjeet Singh", "Leland Takamine", "Arthur Saveliev", "Axel Niklasson", "Berik Visschers"))
        license.set("Apache-2.0")
        copyright.set("mobile.dev 2024")
    }

    distributions {
        create("maestro") {
            stereotype.set(Stereotype.CLI)

            executable {
                name.set("maestro")
            }

            artifact {
                setPath("build/distributions/maestro.zip")
            }

            release {
                github {
                    repoOwner.set("mobile-dev-inc")
                    name.set("maestro")
                    tagName.set("cli-$CLI_VERSION")
                    releaseName.set("CLI $CLI_VERSION")
                    overwrite.set(true)

                    changelog {
                        // GitHub removes dots Markdown headers (1.37.5 becomes 1375)
                        extraProperties.put("versionHeader", CLI_VERSION.replace(".", ""))

                        formatted.set(ALWAYS)
                        content.set("""
                            [See changelog in the CHANGELOG.md file][link]

                            [link]: https://github.com/mobile-dev-inc/maestro/blob/main/CHANGELOG.md#{{changelogVersionHeader}}
                        """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    packagers {
        brew {
            setActive("RELEASE")
            extraProperties.put("skipJava", "true")
            formulaName.set("Maestro")

            // The default template path
            templateDirectory.set(file("src/jreleaser/distributions/maestro/brew"))

            repoTap {
                repoOwner.set("mobile-dev-inc")
                name.set("homebrew-tap")
            }

            dependencies {
                dependency("openjdk", "17+")
            }
        }
    }
}
