plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.android.tools.build:gradle:8.5.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.21")
}

gradlePlugin {
    plugins {
        register("airosAndroidApplication") {
            id = "airos.android.application"
            implementationClass = "AirosAndroidApplicationConventionPlugin"
        }
        register("airosAndroidLibrary") {
            id = "airos.android.library"
            implementationClass = "AirosAndroidLibraryConventionPlugin"
        }
        register("airosAndroidComposeLibrary") {
            id = "airos.android.compose.library"
            implementationClass = "AirosAndroidComposeLibraryConventionPlugin"
        }
        register("airosKotlinLibrary") {
            id = "airos.kotlin.library"
            implementationClass = "AirosKotlinLibraryConventionPlugin"
        }
    }
}
