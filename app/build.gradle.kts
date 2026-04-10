plugins {
    id("airos.android.application")
}

android {
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    implementation(project(":domain"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:tablemap"))
    implementation(project(":feature:ticket"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:kitchen"))
    implementation(project(":feature:payment"))
    implementation(project(":feature:shift"))
    implementation(project(":feature:scanner"))
    implementation(project(":feature:camera"))
    implementation(project(":feature:settings"))
    implementation(project(":device:printer"))
    implementation(project(":device:scanner"))
    implementation(project(":device:camera"))
    implementation(project(":device:cashdrawer"))
    implementation(project(":device:platform"))
    implementation(project(":sync"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(files("$rootDir/libs/google-webrtc-vetted.aar"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
