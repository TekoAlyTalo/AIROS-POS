plugins {
    id("airos.android.library")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp.core)
    implementation(files("$rootDir/libs/google-webrtc-vetted.aar"))
}
