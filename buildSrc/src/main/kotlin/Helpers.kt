import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.github.triplet.gradle.play.PlayPublisherExtension
import org.apache.commons.codec.binary.Base64InputStream
import org.apache.tools.ant.filters.StringInputStream
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.*

private val Project.android get() = extensions.getByName<BaseExtension>("android")

private val javaVersion = JavaVersion.VERSION_1_8
private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("sager.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {
            localProperties.load(Base64InputStream(StringInputStream(base64)))
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion("30.0.3")
        compileSdkVersion(30)
        defaultConfig {
            minSdkVersion(21)
            targetSdkVersion(30)
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        lintOptions {
            disable("MissingTranslation")
            disable("ExtraTranslation")
            disable("BlockedPrivateApi")
        }
        packagingOptions {
            exclude("**/*.kotlin_*")
            exclude("/META-INF/*.version")
            exclude("/META-INF/native-image/**")
            exclude("/META-INF/INDEX.LIST")
            exclude("DebugProbesKt.bin")
        }
        packagingOptions {
            jniLibs.useLegacyPackaging = true
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                }
            }
            applicationVariants.forEach { variant ->
                variant.outputs.forEach {
                    it as BaseVariantOutputImpl
                    it.outputFileName = it.outputFileName
                        .replace("app", "${project.name}-" + variant.versionName)
                        .replace("-release", "")
                        .replace("-oss", "")
                }
            }
        }
    }
}

fun Project.setupKotlinCommon() {
    setupCommon()
    (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
        jvmTarget = javaVersion.toString()
    }
    dependencies.apply {
        add("implementation", kotlin("stdlib-jdk8"))
    }
}

fun Project.setupNdk() {
    android.ndkVersion = "21.4.7075529"
}

fun Project.setupNdkLibrary() {
    setupCommon()
    setupNdk()
    android.apply {
        defaultConfig {
            externalNativeBuild.ndkBuild {
                abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                arguments("-j${Runtime.getRuntime().availableProcessors()}")
            }
        }

        externalNativeBuild.ndkBuild.path("src/main/jni/Android.mk")
    }
}

fun Project.setupPlay(): PlayPublisherExtension {
    apply(plugin = "com.github.triplet.play")
    return (extensions.getByName("play") as PlayPublisherExtension).apply {
        track.set("beta")
        defaultToAppBundles.set(true)
    }
}

fun Project.setupAppCommon() {
    setupKotlinCommon()

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    val serviceAccountCredentialsFile = rootProject.file("service_account_credentials.json")
    if (serviceAccountCredentialsFile.isFile) {
        setupPlay().serviceAccountCredentials.set(serviceAccountCredentialsFile)
    } else if (System.getenv().containsKey("ANDROID_PUBLISHER_CREDENTIALS")) {
        setupPlay()
    }

    android.apply {
        if (keystorePwd != null) {
            signingConfigs {
                create("release") {
                    storeFile(rootProject.file("release.keystore"))
                    storePassword(keystorePwd)
                    keyAlias(alias)
                    keyPassword(pwd)
                }
            }
        }
        buildTypes {
            getByName("release").signingConfig = if (keystorePwd != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }

    }
}

fun Project.setupPlugin(project: String) {
    val propPrefix = project.toUpperCase(Locale.ROOT)
    val verName = requireMetadata().getProperty("${propPrefix}_VERSION_NAME")
    val verCode = requireMetadata().getProperty("${propPrefix}_VERSION").toInt() * 5
    android.defaultConfig {
        applicationId = "io.nekohasekai.sagernet.plugin.${project.toLowerCase(Locale.ROOT)}"

        versionName = verName
        versionCode = verCode
    }

    apply(plugin = "kotlin-android")

    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), project(":plugin:api").file("proguard-rules.pro"))
            }
        }

        splits.abi {
            isEnable = true
            isUniversalApk = false

            var targetAbi = ""
            if (gradle.startParameter.taskNames.isNotEmpty()) {
                if (gradle.startParameter.taskNames.size == 1) {
                    val targetTask = gradle.startParameter.taskNames[0].toLowerCase(Locale.ROOT)
                    when {
                        targetTask.contains("arm64") -> targetAbi = "arm64-v8a"
                        targetTask.contains("arm") -> targetAbi = "armeabi-v7a"
                        targetTask.contains("x64") -> targetAbi = "x86_64"
                        targetTask.contains("x86") -> targetAbi = "x86"
                    }
                }
            }

            if (targetAbi.isNotBlank()) {
                reset()
                include(targetAbi)
            }
        }

        flavorDimensions("vendor")
        productFlavors {
            create("oss")
            create("fdroidArm64") {
                versionNameSuffix = "-arm64"
            }
            create("fdroidArm") {
                versionCode = verCode - 1
                versionNameSuffix = "-arm"
            }
            create("fdroidX64") {
                versionCode = verCode - 2
                versionNameSuffix = "-x64"
            }
            create("fdroidX86") {
                versionCode = verCode - 3
                versionNameSuffix = "-x86"
            }
            create("play") {
                versionCode = verCode - 4
            }
        }

        applicationVariants.forEach { variant ->
            variant.outputs.forEach {
                it as BaseVariantOutputImpl
                it.outputFileName = it.outputFileName
                    .replace(name, "$name-plugin-" + variant.versionName)
                    .replace("-release", "")
                    .replace("-oss", "")

            }
        }
    }

    dependencies.add("implementation", project(":plugin:api"))
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val skip = 40
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt() - skip) * 5 + skip
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
            }
        }

        splits.abi {
            isEnable = true
            isUniversalApk = false

            var targetAbi = ""
            if (gradle.startParameter.taskNames.isNotEmpty()) {
                if (gradle.startParameter.taskNames.size == 1) {
                    val targetTask = gradle.startParameter.taskNames[0].toLowerCase(Locale.ROOT)
                    when {
                        targetTask.contains("arm64") -> targetAbi = "arm64-v8a"
                        targetTask.contains("arm") -> targetAbi = "armeabi-v7a"
                        targetTask.contains("x64") -> targetAbi = "x86_64"
                        targetTask.contains("x86") -> targetAbi = "x86"
                    }
                }
            }

            if (targetAbi.isNotBlank()) {
                reset()
                include(targetAbi)
            }
        }

        flavorDimensions("vendor")
        productFlavors {
            create("oss")
            create("expert")
            create("fdroidArm64") {
                versionNameSuffix = "-arm64"
            }
            create("fdroidArm") {
                versionCode = verCode - 1
                versionNameSuffix = "-arm"
            }
            create("fdroidX64") {
                versionCode = verCode - 2
                versionNameSuffix = "-x64"
            }
            create("fdroidX86") {
                versionCode = verCode - 3
                versionNameSuffix = "-x86"
            }
            create("play") {
                versionCode = verCode - 4
            }
        }

        applicationVariants.forEach { variant ->
            variant.outputs.forEach {
                it as BaseVariantOutputImpl
                it.outputFileName = it.outputFileName
                    .replace("app", "SN-" + variant.versionName)
                    .replace("-release", "")
                    .replace("-oss", "")

            }
        }
    }

    dependencies {
        add("implementation", project(":plugin:api"))
        add("testImplementation", "junit:junit:4.13.2")
        add("androidTestImplementation", "androidx.test.ext:junit:1.1.2")
        add("androidTestImplementation", "androidx.test:runner:1.3.0")
        add("androidTestImplementation", "androidx.test.espresso:espresso-core:3.3.0")
    }
}