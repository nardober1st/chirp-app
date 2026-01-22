plugins {
    id("chirp.spring-boot-app")
}

group = "com.bernardooechsler"
version = "0.0.1-SNAPSHOT"
description = "Chirp Backend"

dependencies {
    implementation(projects.user)
    implementation(projects.common)
    implementation(projects.notification)
    implementation(projects.chat)

    implementation(libs.spring.boot.starter.data.jpa)
    runtimeOnly(libs.postgresql)
}