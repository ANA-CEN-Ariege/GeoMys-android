plugins {
    alias(libs.plugins.android.application) apply false
    // Pas d'alias Kotlin : jamais appliqué dans app/ — c'est le Kotlin embarqué par AGP qui
    // compile. L'alias et sa version dans le TOML étaient décoratifs et trompeurs (audit).
}
