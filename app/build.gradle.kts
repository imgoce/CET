import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 팀 DB(Supabase) 접속 정보: 프로젝트 루트 local.properties (커밋하지 않음)에 넣는다.
//   supabase.url=https://<project>.supabase.co
//   supabase.key=sb_publishable_...
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.opensrc.cet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.opensrc.cet"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${localProps.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${localProps.getProperty("supabase.key", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)  // 단위 테스트(PC)용 org.json. 앱에서는 Android 내장 org.json 을 쓴다
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}