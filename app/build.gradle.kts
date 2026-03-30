import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject
import org.gradle.process.ExecOperations
import java.io.File
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.provider.Provider

fun Project.getSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name)

val releaseSigningValues = mapOf(
    "RELEASE_STORE_FILE" to project.getSigningValue("RELEASE_STORE_FILE"),
    "RELEASE_STORE_PASSWORD" to project.getSigningValue("RELEASE_STORE_PASSWORD"),
    "RELEASE_KEY_ALIAS" to project.getSigningValue("RELEASE_KEY_ALIAS"),
    "RELEASE_KEY_PASSWORD" to project.getSigningValue("RELEASE_KEY_PASSWORD")
)
val releaseSigningMissingKeys = releaseSigningValues
    .filterValues { it.isNullOrBlank() }
    .keys
    .toList()
val hasReleaseSigning = releaseSigningMissingKeys.isEmpty()

val releaseSigningSetupHint = """
Set the missing values in either:
- ~/.gradle/gradle.properties
- Environment variables

Required keys:
- RELEASE_STORE_FILE
- RELEASE_STORE_PASSWORD
- RELEASE_KEY_ALIAS
- RELEASE_KEY_PASSWORD
""".trimIndent()

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

// Custom task type for building frontend
abstract class BuildFrontendTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    
    // Declare frontend source directory as input for incremental builds
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frontendSrcDir: DirectoryProperty
    
    // Declare output directory for up-to-date checks
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    // Internal property to store root project directory
    @get:Internal
    abstract val rootProjectDir: DirectoryProperty
    
    init {
        group = "frontend"
        description = "Build frontend assets using pnpm"
    }
    
    @TaskAction
    fun buildFrontend() {
        // Get the root project directory from the internal property
        val rootDir = rootProjectDir.get().asFile
        val frontendDir = File(rootDir, "frontend")
        val scriptsDir = File(rootDir, "scripts")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        
        if (!frontendDir.exists()) {
            logger.warn("Frontend directory not found, skipping build")
            return
        }
        
        logger.info("Building frontend in ${frontendDir.absolutePath}")
        
        val command = if (isWindows) {
            listOf("powershell", "-ExecutionPolicy", "Bypass", "-File", 
                File(scriptsDir, "build-android.ps1").absolutePath)
        } else {
            listOf("pnpm", "run", "build:android")
        }
        
        execOperations.exec {
            workingDir(frontendDir)
            commandLine(command)
        }
    }
}

// Register and configure the task
val buildFrontendProvider = tasks.register("buildFrontend", BuildFrontendTask::class.java) {
    // Only set frontend source directory as input - this is what we want to watch for changes
    frontendSrcDir.set(File(rootProject.projectDir, "frontend/src"))
    // Set output directory for up-to-date checks
    outputDir.set(File(rootProject.projectDir, "app/src/main/assets/amll"))
    // Set root project directory for task execution
    rootProjectDir.set(rootProject.layout.projectDirectory)
}

// Build frontend before preBuild task
tasks.named("preBuild") {
    dependsOn(buildFrontendProvider)
}

// Use Provider API for lazy evaluation - ensures fresh timestamp on each build
val buildTimestampProvider: Provider<String> = providers.provider {
    SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
}

android {
    namespace = "com.amll.droidmate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amll.droidmate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "Alpha ${buildTimestampProvider.get()}" // 版本号
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValues.getValue("RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")!!
                keyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")!!
                keyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")!!
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    lint {
        // 仅禁用 FullBackupContent，重新启用 NetworkSecurityConfig 以检查网络安全问题
        disable += listOf("FullBackupContent")
    }
}

val releaseSigningValidationMessage =
    "Missing release signing properties: ${releaseSigningMissingKeys.joinToString(", ")}\n$releaseSigningSetupHint"

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "assembleRelease" || name == "bundleRelease" || name == "packageRelease") {
        doFirst {
            if (!hasReleaseSigning) {
                throw GradleException(releaseSigningValidationMessage)
            }
        }
    }
}

// Rename APKs using the modern onVariants API
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "AMLL-DroidMate-Alpha-${buildTimestampProvider.get()}.apk" //版本号
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))

    // AndroidX
    implementation("androidx.core:core-ktx:1.18.0")
    // 1.1.0 never reached a final release; pick a published version
    // (1.2.0 is the latest stable as of this writing)
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.palette:palette:1.0.0")

    // media3 UI gives us DefaultTimeBar and other player controls
    implementation("androidx.media3:media3-ui:1.0.0")
    implementation("androidx.webkit:webkit:1.8.0")
    // Jetpack Compose (Version managed by BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Networking (Ktor 3.x)
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-okhttp:3.4.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-client-serialization:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Chinese simplified/traditional conversion (used for improved track matching)
    implementation("com.github.houbb:opencc4j:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Database (Room)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-client-mock:3.4.1")
    testImplementation("io.ktor:ktor-client-mock-jvm:3.4.1")
    // make the mock engine available to instrumented Android tests as well
    androidTestImplementation("io.ktor:ktor-client-mock:3.4.1")
    androidTestImplementation("io.ktor:ktor-client-mock-jvm:3.4.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
