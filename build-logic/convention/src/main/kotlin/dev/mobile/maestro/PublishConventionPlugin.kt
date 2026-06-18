package dev.mobile.maestro

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Configures publishing to Maven Central via the vanniktech maven-publish plugin,
 * signing all publications. Apply this to every module that ships an artifact.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("maven-publish")
                apply("com.vanniktech.maven.publish")
            }

            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral(true)
                signAllPublications()
            }
        }
    }
}
