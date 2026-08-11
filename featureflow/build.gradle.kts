import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

// Single source of truth for the published version. Must match FeatureflowVersion.CURRENT in
// RestClient.kt, which is sent as the X-Featureflow-Client header — CI fails a release if they
// disagree.
version = "0.1.1"
group = "io.featureflow"

android {
    namespace = "io.featureflow.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testOptions.targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // org.json ships as stubs that throw in unit tests; Robolectric supplies the real
            // implementation. Without this, every model test fails on `JSONObject`.
            isReturnDefaultValues = false
        }
    }

}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    // The new Central Portal, not the retired OSSRH/Nexus endpoints. The Java SDK already
    // publishes this way (central-publishing-maven-plugin), under the same io.featureflow
    // namespace and with the same GPG key.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // Sign unless explicitly opted out with -PskipSigning.
    //
    // This was previously the other way round — sign only *if* a key looked present — so that
    // local publishToMavenLocal worked without one. That condition silently evaluated false in
    // CI as well, producing an unsigned bundle Central would have rejected, and an input check
    // ("is the secret set?") could not catch it. Defaulting to signing means a misconfiguration
    // fails loudly; opting out is now a deliberate, visible flag.
    //
    //   ./gradlew :featureflow:publishToMavenLocal -PskipSigning=true
    if (!project.hasProperty("skipSigning")) {
        signAllPublications()
    }

    coordinates(group.toString(), "featureflow-android-sdk", version.toString())

    pom {
        name.set("Featureflow Android SDK")
        description.set(
            "Featureflow client SDK for Android — feature flags, gradual rollouts and experiments."
        )
        url.set("https://github.com/featureflow/featureflow-android-sdk")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("featureflow")
                name.set("Featureflow")
                email.set("support@featureflow.io")
                url.set("https://featureflow.io")
            }
        }
        scm {
            url.set("https://github.com/featureflow/featureflow-android-sdk")
            connection.set("scm:git:git://github.com/featureflow/featureflow-android-sdk.git")
            developerConnection.set(
                "scm:git:ssh://git@github.com/featureflow/featureflow-android-sdk.git"
            )
        }
    }
}
