package io.featureflow.android

/** Logs to logcat under the `Featureflow` tag. Opt in via [FeatureflowConfig.logger]. */
class AndroidLogcatLogger(
    private val minimumLevel: FeatureflowLogLevel = FeatureflowLogLevel.INFO
) : FeatureflowLogger {

    override fun log(level: FeatureflowLogLevel, message: String) {
        if (level.ordinal < minimumLevel.ordinal) return
        when (level) {
            FeatureflowLogLevel.DEBUG -> android.util.Log.d(TAG, message)
            FeatureflowLogLevel.INFO -> android.util.Log.i(TAG, message)
            FeatureflowLogLevel.WARN -> android.util.Log.w(TAG, message)
            FeatureflowLogLevel.ERROR -> android.util.Log.e(TAG, message)
        }
    }

    private companion object {
        const val TAG = "Featureflow"
    }
}
