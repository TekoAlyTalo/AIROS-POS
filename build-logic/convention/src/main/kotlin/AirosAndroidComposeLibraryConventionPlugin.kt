import org.gradle.api.Plugin
import org.gradle.api.Project

class AirosAndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        configureAndroidLibrary(withCompose = true)
    }
}
