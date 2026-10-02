plugins {
    kotlin("jvm") version "2.0.21"
    // 打包 fat jar（把 sqlite-jdbc / gson 一起塞進去）
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.cymetrics"
// 平常用預設值；發版時由 release workflow 以 -PappVersion=<tag 去掉 v> 覆寫
version = (findProperty("appVersion") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
}

// Montoya API 版本：請對應你的 Burp 版本，通常新版本向後相容。
val montoyaVersion = "2023.12.1"

dependencies {
    // Burp 執行時會自己提供 Montoya，所以只編譯用、不要打包進 jar
    compileOnly("net.portswigger.burp.extensions:montoya-api:$montoyaVersion")

    // 這兩個要打包進 jar（Burp 不提供）
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveClassifier.set("") // 輸出 burp-es-logger-0.1.0.jar
    // Burp 每個 extension 有獨立 classloader，通常不需要 relocation。
    // 若和其他 extension 衝突，再開啟下面這行：
    // relocate("com.google.gson", "io.cymetrics.eslogger.shaded.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
