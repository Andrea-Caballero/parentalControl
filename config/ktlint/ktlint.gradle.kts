// ktlint configuration
// T00: Code style enforcement

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("0.50.0")
    android.set(true)
    outputColorName.set("RED")
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
        include("**/*.kt")
        include("**/*.kts")
    }
    kotlinScript {
        warningsAsErrors.set(false)
        outputColorName.set("RED")
    }
}
