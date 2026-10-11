// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.spotless)
}

spotless {
  kotlin {
    target("**/*.kt")
    targetExclude("**/build/**", "**/.gradle/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    targetExclude("**/build/**", "**/.gradle/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  format("misc") {
    target("**/*.md", "**/*.toml", "**/*.xml", "**/.gitignore")
    targetExclude("**/build/**", "**/.gradle/**")
    trimTrailingWhitespace()
    endWithNewline()
  }
}
