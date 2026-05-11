import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.gitee.prviewer"
version = "0.1.8"

repositories {
    maven("https://maven.aliyun.com/repository/public/")
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
        url = uri("https://maven.aliyun.com/repository/jcenter")
        url = uri("https://maven.aliyun.com/repository/google")
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

intellij {
    version.set("2024.1")
    type.set("IC")
    plugins.set(listOf("Git4Idea"))
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("241.*")
}

tasks.runIde {
    environment("USERID", "zhangbo1")
}
