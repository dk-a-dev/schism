plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bank-SMS parsing, vendored from pennywise's parser-core (pure Kotlin, no Android deps). Consumed
// on-device to turn transaction SMS into ParsedTransaction; SMS content never leaves the device.
dependencies {
    api(libs.kotlinx.datetime)
}

kotlin {
    jvmToolchain(17)
}
