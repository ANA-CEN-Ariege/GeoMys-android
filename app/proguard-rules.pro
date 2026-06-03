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
# Modèles désérialisés par réflexion (cf. audit B2). On garde le constructeur et
# les champs : R8 ne doit ni les renommer ni les supprimer.
-keep class fr.ariegenature.geomys.network.GeoNatureDataset { *; }
-keep class fr.ariegenature.geomys.network.GeoNatureListe { *; }
-keep class fr.ariegenature.geomys.network.GeoNatureObservateur { *; }
-keep class fr.ariegenature.geomys.network.AdditionalFieldDef { *; }
-keep class fr.ariegenature.geomys.store.SaisieEnAttente { *; }
-keep class fr.ariegenature.geomys.store.TaxRefEntry { *; }
-keep class fr.ariegenature.geomys.store.NomValeur { *; }
-keep class fr.ariegenature.geomys.model.Denombrement { *; }
-keep class fr.ariegenature.geomys.model.Sortie { *; }