# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Gson — préventif. isMinifyEnabled=false aujourd'hui ; ces règles évitent que
# l'activation future de R8 casse le parsing par réflexion (TypeToken, champs).
# ---------------------------------------------------------------------------
# Signatures génériques nécessaires aux TypeToken<List<...>> / Map<...>.
-keepattributes Signature
-keepattributes *Annotation*
# Gson interne + sous-classes anonymes de TypeToken (object : TypeToken<...>(){}).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Modèles désérialisés par réflexion (cf. audit B2 + audit 2026-07). On garde le
# constructeur et les champs : R8 ne doit ni les renommer ni les supprimer.
# ⚠ La liste était INCOMPLÈTE (audit 2026-07) : Sortie était gardée mais pas ses classes
# IMBRIQUÉES (Observation, PointTrace, Taxon) ni HabitatSuggestion — activer R8 avec
# l'ancienne liste aurait cassé silencieusement la RELECTURE des saisies persistées
# (SortieStore) au premier démarrage minifié. Aucune de ces classes n'a de @SerializedName,
# la règle keepclassmembers @SerializedName ne les protège donc pas.
# Le package model est gardé EN ENTIER : toute classe qu'on y ajoutera (le cas le plus
# fréquent d'évolution du format de saisie) sera protégée d'office.
-keep class fr.ariegenature.geomys.model.** { *; }
-keep class fr.ariegenature.geomys.network.GeoNatureDataset { *; }
-keep class fr.ariegenature.geomys.network.GeoNatureListe { *; }
-keep class fr.ariegenature.geomys.network.GeoNatureObservateur { *; }
-keep class fr.ariegenature.geomys.network.AdditionalFieldDef { *; }
-keep class fr.ariegenature.geomys.network.HabitatSuggestion { *; }
# SaisieEnAttente$Etat (enum imbriqué, sérialisé par nom) : couvert par le $*.
-keep class fr.ariegenature.geomys.store.SaisieEnAttente { *; }
-keep class fr.ariegenature.geomys.store.SaisieEnAttente$* { *; }
-keep class fr.ariegenature.geomys.store.TaxRefEntry { *; }
-keep class fr.ariegenature.geomys.store.NomValeur { *; }
# Table locale des codes de nidification (classe privée Fichier parsée par Gson).
-keep class fr.ariegenature.geomys.store.NidificationOiseaux$* { *; }