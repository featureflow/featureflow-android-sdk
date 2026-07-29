import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.featureflow.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.featureflow.example"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Supply the key without committing it:
        //   echo "featureflow.clientKey=sdk-js-env-xxxx" >> local.properties
        val localProperties = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) localProperties.load(localFile.inputStream())
        buildConfigField(
            "String",
            "FEATUREFLOW_CLIENT_KEY",
            "\"${localProperties.getProperty("featureflow.clientKey") ?: ""}\""
        )
        buildConfigField(
            "String",
            "FEATUREFLOW_BASE_URL",
            "\"${localProperties.getProperty("featureflow.baseUrl") ?: "https://app.featureflow.io"}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":featureflow"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
