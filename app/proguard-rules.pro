# Shrink and optimize, but keep names and line numbers so crash logs stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# lz4-java picks its compressor implementation by class name.
-keep class net.jpountz.** { *; }

# Onyx SDK: reflection into hidden APIs and its own classes; it ships no rules.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**
# fastjson2, used by the Onyx SDK, (de)serializes by reflection.
-keep class com.alibaba.fastjson2.** { *; }
-dontwarn com.alibaba.fastjson2.**

# MuPDF: native code calls back into these classes.
-keep class com.artifex.mupdf.fitz.** { *; }
# Optional annotations joda-time (from the Onyx SDK) refers to.
-dontwarn org.joda.convert.**

# Automerge: the JNI library calls back into these classes by name.
-keep class org.automerge.** { *; }
# QuickJS (live code image, ADR-002): JNI bindings and the values it hands across.
-keep class com.dokar.quickjs.** { *; }
# The REPL's WebSocket server.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
