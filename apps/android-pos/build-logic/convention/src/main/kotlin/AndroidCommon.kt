import org.gradle.api.JavaVersion
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

private const val COMPILE_SDK = 35
private const val MIN_SDK = 26
private const val TARGET_SDK = 35

internal fun Project.configureAndroidApplication() {
    extensions.configure<ApplicationExtension> {
        namespace = namespaceFromPath(project.path)
        compileSdk = COMPILE_SDK

        defaultConfig {
            applicationId = "com.airos.pos.app"
            minSdk = MIN_SDK
            targetSdk = TARGET_SDK
            versionCode = 1
            versionName = "1.0.0-alpha01"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        configureAndroidCommon(this)
        buildFeatures.compose = true
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

internal fun Project.configureAndroidLibrary(withCompose: Boolean) {
    extensions.configure<LibraryExtension> {
        namespace = namespaceFromPath(project.path)
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        configureAndroidCommon(this)
        buildFeatures.compose = withCompose
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

internal fun Project.configureJvmLibrary() {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }
}

private fun Project.namespaceFromPath(path: String): String {
    if (path == ":app") {
        return "com.airos.pos.app"
    }
    return "com.airos.pos" + path.split(":")
        .filter { it.isNotBlank() }
        .joinToString(separator = ".", prefix = ".") { it.replace("-", "") }
}

private fun configureAndroidCommon(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        defaultConfig {
            vectorDrawables.useSupportLibrary = true
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
        testOptions {
            animationsDisabled = true
        }
    }
}
