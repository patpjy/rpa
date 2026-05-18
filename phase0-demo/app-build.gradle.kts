// 把这个文件的 dependencies 块合并到你 Android Studio 工程的 app/build.gradle.kts
// 工程其他部分(plugins、android {} 块的 namespace/minSdk/targetSdk 等)用 AS 生成的默认即可,只需保证:
//   - namespace = "com.example.dyrpa"
//   - minSdk = 26
//   - targetSdk = 34
//   - compileSdk = 34
//   - kotlinOptions.jvmTarget = "17"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // ===== Shizuku / Stellar SDK(关键)=====
    // 如果 13.1.5 拉不到,去 https://central.sonatype.com/artifact/dev.rikka.shizuku/api 查最新版
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ===== 心跳 HTTP POST =====
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lifecycle(可选)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
}
