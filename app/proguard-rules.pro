# LiteRT-LM SDK (no consumer rules shipped)
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx：JNI 按类名/方法名反射绑定，不能混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
