plugins {
    `java-library`
}

dependencies {
    api(project(":engine"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly(libs.junit.platform.launcher)
}
