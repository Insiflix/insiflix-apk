import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.privatestream"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.insiflix.apkcontainer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Change the URL to your actual HTTPS streaming frontend.
        buildConfigField("String", "STREAM_URL", "\"https://insiflix.com/\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Preserve the website's original PNG in a text-safe source asset and generate
// the Android resource during builds, without network or frontend-repo access.
abstract class GenerateWebsiteIcon : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: org.gradle.api.file.RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun generate() {
        val icon = outputDirectory.file("drawable-nodpi/insiflix_logo.png").get().asFile
        icon.parentFile.mkdirs()
        icon.writeBytes(Base64.getMimeDecoder().decode(sourceFile.get().asFile.readText()))
    }
}

val generateWebsiteIcon = tasks.register<GenerateWebsiteIcon>("generateWebsiteIcon") {
    sourceFile.set(layout.projectDirectory.file("branding/website-icon.png.base64"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res/websiteIcon"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateWebsiteIcon) { it.outputDirectory }
        val variantName = variant.name
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
        // Export a stable APK filename without changing AGP's internal output metadata.
        val exportApk = tasks.register<Copy>("exportInsiflix${capitalizedName}Apk") {
            from(layout.buildDirectory.dir("outputs/apk/$variantName")) {
                include("*.apk")
                rename { "insiflix.apk" }
            }
            into(layout.buildDirectory.dir("outputs/insiflix/$variantName"))
        }
        tasks.matching { it.name == "assemble$capitalizedName" }.configureEach {
            finalizedBy(exportApk)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.12.4")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
}
