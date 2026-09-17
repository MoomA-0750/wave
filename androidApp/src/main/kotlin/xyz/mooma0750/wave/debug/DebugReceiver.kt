package xyz.mooma0750.wave.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.mooma0750.wave.WaveApplication

/** debug ビルドのマニフェストだけに、DUMP 権限付きで登録する（adb shell は持つが、普通のアプリは持てない）。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        DebugActions.handle(intent, (context.applicationContext as WaveApplication).container, fromPrivilegedReceiver = true)
    }
}
