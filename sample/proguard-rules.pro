# ReleaseKite Sample 的 R8 规则。
#
# 库模块通过 consumer-rules.pro 自带关键 keep 规则（例如 WorkManager 反射实例化的
# ReleaseKiteDownloadWorker 构造器），示例应用本身没有反射/序列化代码，因此这里只需：
# - 保留 manifest 引用的入口组件（Android 默认会保留组件，显式写出便于阅读）；
# - 保留行号信息，便于示例在 release 变体下排障。
-keep class io.github.releasekite.sample.SampleApplication { *; }
-keep class io.github.releasekite.sample.MainActivity { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
