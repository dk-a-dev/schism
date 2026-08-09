-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# Retrofit reads service method/parameter annotations and generic return signatures.
-keep,allowoptimization,allowshrinking interface ai.schism.split.core.net.ApiService
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Room/Hilt ship consumer rules; these entry points are additionally created by platform reflection.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class ai.schism.split.ocr.OcrModelDownloadWorker { *; }
-keep class ai.schism.split.sms.ingest.**Worker { *; }

# Native-backed optional on-device engines.
-keep class com.paddle.ocr.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep class com.google.mediapipe.** { *; }

# MediaPipe's text-only GenAI AAR has signatures for optional vision support and protobuf compiler
# annotations that are intentionally not packaged. Schism never calls its image-input APIs.
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
