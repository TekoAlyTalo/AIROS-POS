plugins {
    id("airos.android.library")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation("com.sunmi:printerlibrary:1.0.24")
    implementation("com.sunmi:printerx:1.0.18")
}
