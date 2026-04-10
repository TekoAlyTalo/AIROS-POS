plugins {
    id("airos.android.library")
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
