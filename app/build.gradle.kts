import java.util.Base64
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.privatestream"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.insiflix.apkcontainer"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.5"
        // Change the URL to your actual HTTPS streaming frontend.
        buildConfigField("String", "STREAM_URL", "\"https://insiflix.com/\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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

        // TV launchers need a landscape bitmap, rather than a padded adaptive icon.
        val logo = javax.imageio.ImageIO.read(icon)
        var left = logo.width
        var top = logo.height
        var right = -1
        var bottom = -1
        for (y in 0 until logo.height) {
            for (x in 0 until logo.width) {
                val pixel = Color(logo.getRGB(x, y), true)
                if (pixel.alpha > 0 && pixel.red > 60 &&
                    pixel.red > pixel.green * 1.5 && pixel.red > pixel.blue * 1.5) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        check(right >= left && bottom >= top) { "Fox artwork was not found in the source icon" }
        val artwork = logo.getSubimage(left, top, right - left + 1, bottom - top + 1)
        val banner = BufferedImage(432, 243, BufferedImage.TYPE_INT_RGB)
        val graphics = banner.createGraphics()
        try {
            graphics.color = Color(logo.getRGB(0, 0))
            graphics.fillRect(0, 0, banner.width, banner.height)
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
            )
            val scale = minOf(300.0 / artwork.width, 170.0 / artwork.height)
            val width = (artwork.width * scale).toInt()
            val height = (artwork.height * scale).toInt()
            graphics.drawImage(artwork, (banner.width - width) / 2,
                (banner.height - height) / 2, width, height, null)
        } finally {
            graphics.dispose()
        }
        javax.imageio.ImageIO.write(banner, "png",
            outputDirectory.file("drawable-nodpi/insiflix_tv_banner.png").get().asFile)
        val tvIcon = outputDirectory.file("mipmap-television-nodpi/ic_launcher.png").get().asFile
        tvIcon.parentFile.mkdirs()
        // Fire TV normalizes sideloaded launcher icons to a square. A wide icon
        // is squeezed horizontally before the launcher adds its own side panels.
        val square = BufferedImage(243, 243, BufferedImage.TYPE_INT_RGB)
        val iconGraphics = square.createGraphics()
        try {
            iconGraphics.color = Color(logo.getRGB(0, 0))
            iconGraphics.fillRect(0, 0, square.width, square.height)
            iconGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val scale = minOf(170.0 / artwork.width, 170.0 / artwork.height)
            val width = (artwork.width * scale).toInt()
            val height = (artwork.height * scale).toInt()
            iconGraphics.drawImage(artwork, (square.width - width) / 2,
                (square.height - height) / 2, width, height, null)
        } finally {
            iconGraphics.dispose()
        }
        javax.imageio.ImageIO.write(square, "png", tvIcon)
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
