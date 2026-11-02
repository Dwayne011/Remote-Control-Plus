// Android TV Remote protocol v2, vendored from amrakshay/android-tv-remote
// (MIT). See LICENSE and NOTICE.md in this directory.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        // protoc output; see NOTICE.md.
        java.srcDir("src/main/java")
    }
}

dependencies {
    api(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
