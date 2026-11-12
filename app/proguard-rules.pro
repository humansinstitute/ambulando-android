# secp256k1 JNI bindings are looked up by name from native code.
-keep class fr.acinq.secp256k1.** { *; }
# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
# Garmin Connect writes canonical class names into cross-process Parcels. These
# identities and CREATOR implementations are a wire contract, including types
# used only by incoming broadcasts / Binder replies.
-keep class com.garmin.android.connectiq.** implements android.os.Parcelable { *; }
-keep class com.garmin.android.connectiq.**$* { *; }
