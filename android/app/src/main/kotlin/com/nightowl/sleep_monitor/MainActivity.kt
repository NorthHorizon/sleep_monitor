package com.nightowl.sleep_monitor

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.nightowl.sleep_monitor.monitor.MonitorBridge

class MainActivity : FlutterActivity() {
    private var monitorBridge: MonitorBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        monitorBridge = MonitorBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        monitorBridge?.onPermissionsResult(requestCode)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        monitorBridge?.dispose()
        monitorBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
