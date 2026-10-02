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
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(kotlin("stdlib"))

    // 測試需要 Montoya 在 classpath 上（主程式是 compileOnly，不會傳遞到 test）
    testImplementation(kotlin("test"))
    testImplementation("net.portswigger.burp.extensions:montoya-api:$montoyaVersion")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

kotlin {
    jvmToolchain(17)
}

// 關掉內建的瘦 jar。它會和 shadowJar 一起躺在 build/libs，但缺少 sqlite-jdbc 與 gson，
// 載進 Burp 會在執行時才炸 —— 兩個 jar 擺在一起只會讓人拿錯。
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    // 檔名不帶版本號：Burp 裡 extension 的路徑是固定的，帶版本的話每次改版都要重新
    // Add 一次，Auto-reload 也會指到舊檔。版本資訊由 git tag 與 release 負責。
    archiveClassifier.set("")
    archiveVersion.set("")    // 輸出 burp-es-logger.jar
    // Burp 每個 extension 有獨立 classloader，通常不需要 relocation。
    // 若和其他 extension 衝突，再開啟下面這行：
    // relocate("com.google.gson", "io.cymetrics.eslogger.shaded.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
