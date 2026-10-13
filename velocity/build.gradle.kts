import xyz.jpenilla.runvelocity.task.RunVelocity

plugins {
    packetevents.`shadow-conventions`
    packetevents.`library-conventions`
    packetevents.`publish-conventions`
    xyz.jpenilla.`run-velocity`
}

repositories {
    mavenCentral()
   // maven("https://papermc.io/repo/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.netty)
    compileOnly(libs.velocity)
    annotationProcessor(libs.velocity)
    shadow(project(":api", "shadow"))
    shadow(project(":netty-common"))
    compileShadowOnly(libs.bstats.velocity)
    testImplementation(libs.netty)
    testImplementation(libs.velocity)
    testImplementation(libs.bundles.adventure)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.2")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.2")
    // Velocity already bundles with adventure
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    test { useJUnitPlatform() }
    compileTestJava { options.release = 25 }
    named<RunVelocity>("runVelocity") {
        velocityVersion("4.1.1")
        jvmArgs("-Dnet.kyori.ansi.colorLevel=indexed16")
        runDirectory = rootDir.resolve("run/velocity/")

        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }
}
