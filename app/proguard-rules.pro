# kotlinx.serialization keeps its own generated serializers; Retrofit needs
# the service interface's generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowobfuscation interface * { @retrofit2.http.* <methods>; }
