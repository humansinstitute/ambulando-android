# secp256k1 JNI bindings are looked up by name from native code.
-keep class fr.acinq.secp256k1.** { *; }
# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
