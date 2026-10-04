# Proguard rules for DubSmash Reborn

# MediaPipe Tasks Vision / AutoValue shaded javapoet rules
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8

# Keep Pigeon generated classes
-keep class com.dubsmash.dsr_engine.Messages** { *; }

# Keep Drift sqlite
-keep class * extends org.sqlite.** { *; }
