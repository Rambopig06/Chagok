import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pleos Playground 앱 등록 키: 저장소 루트의 local.properties (git에 올리지 않음)
//   pleos.clientId=...
//   pleos.clientSecret=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// 릴리스 서명 정보: 저장소 루트의 keystore.properties (git에 올리지 않음)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sciencecrew.chagok"
    compileSdk = 37

    // 속도·주차 브레이크용 AAOS 표준 Car API (기기에는 시스템에 기본 포함)
    useLibrary("android.car")

    defaultConfig {
        // 패키지명은 팀명/앱이름이 정해지면 바꿀 수 있음
        applicationId = "com.sciencecrew.chagok"
        minSdk = 28      // 차량 보안 요구사항: 28 이상 필수
        targetSdk = 34   // 에뮬레이터 이미지(API 34)에 맞춤
        versionCode = 1
        versionName = "0.1.0"

        // 글레오(LLM) 서버 모드용 Playground 키. 없으면 빈 문자열 → 서버 모드 건너뜀
        buildConfigField("String", "PLEOS_CLIENT_ID", quoted(localProps.getProperty("pleos.clientId")))
        buildConfigField("String", "PLEOS_CLIENT_SECRET", quoted(localProps.getProperty("pleos.clientSecret")))
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 차량 보안 요구사항(서명 키 교체·계보) 대응: APK v3 서명 포함
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Pleos Vehicle SDK (공식 문서 Importing Vehicle SDK: 2.3.2)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // Pleos 음성 SDK (공식 문서 Importing SDK: 3.3.1). ON_DEVICE 모드로 사용
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // Pleos 내비 연동 SDK (공식 문서 Importing SDK: 3.0.5)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // Pleos LLM SDK (글레오 AI 도움). 공식 문서 페이지 없음 → 저장소 최신판 2.1.3.2, ON_DEVICE 모드
    implementation("ai.pleos.playground:LLM:2.1.3.2")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}

// 제출용 APK 만들기: ./gradlew exportReleaseApk → 저장소 루트 apk/{팀명}_{앱이름}_release.apk
val teamName = providers.gradleProperty("pleos.teamName").getOrElse("TeamName")
val appName = providers.gradleProperty("pleos.appName").getOrElse("AppName")
tasks.register<Copy>("exportReleaseApk") {
    group = "pleos"
    description = "release APK를 apk/ 폴더에 제출용 이름으로 복사"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "${teamName}_${appName}_release.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("apk"))
}
