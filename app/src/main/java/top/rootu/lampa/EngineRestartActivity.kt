package top.rootu.lampa

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Replaces the browser process when switching between incompatible Chromium JNI runtimes. */
class EngineRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previousPid = intent.getIntExtra(EXTRA_PREVIOUS_PID, -1)
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        fun previousProcessExists() = manager.runningAppProcesses.orEmpty().any {
            it.pid == previousPid && it.uid == Process.myUid() && it.processName == packageName
        }

        // This Activity is not exported, and only our own browser process is stopped.
        if (previousProcessExists()) Process.killProcess(previousPid)
        val handler = Handler(Looper.getMainLooper())
        val relaunch = object : Runnable {
            override fun run() {
                // Let ActivityManager observe the death before routing the new launch.
                if (previousProcessExists()) {
                    handler.postDelayed(this, 25)
                    return
                }
                startActivity(Intent.makeRestartActivityTask(ComponentName(
                    this@EngineRestartActivity, MainActivity::class.java
                )))
                finish()
                Process.killProcess(Process.myPid())
            }
        }
        handler.post(relaunch)
    }

    companion object {
        private const val EXTRA_PREVIOUS_PID = "previous_browser_pid"

        fun restart(activity: Activity) {
            activity.startActivity(Intent(activity, EngineRestartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_PREVIOUS_PID, Process.myPid())
            })
        }
    }
}
