# ReleaseKite 消费方 R8 规则。
# WorkManager 通过类名反射实例化 Worker，必须保留构造器。
-keep class io.github.releasekite.internal.ReleaseKiteDownloadWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
