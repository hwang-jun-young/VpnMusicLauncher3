package com.ytmusic.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ytmusic.launcher.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var vpnCheckRunnable: Runnable? = null
    private var isConnecting = false

    companion object {
        const val WIREGUARD_PACKAGE = "com.wireguard.android"
        const val MORF_PACKAGE = "app.morphe.android.apps.youtube.music"
        const val VPN_CHECK_INTERVAL_MS = 2000L
        const val VPN_TIMEOUT_MS = 30000L
    }

    private val morfStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MorfWatcherService.ACTION_MORF_STOPPED) {
                disconnectWireGuard()
                updateStatus(Status.IDLE)
                showToast("모프 종료 → WireGuard 해제")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(
            morfStoppedReceiver,
            IntentFilter(MorfWatcherService.ACTION_MORF_STOPPED),
            RECEIVER_NOT_EXPORTED
        )

        binding.btnStart.setOnClickListener { startProcess() }
        binding.btnCancel.setOnClickListener { cancelConnection() }
        binding.btnOpenVpn.setOnClickListener { openWireGuard() }
        binding.btnMorf.setOnClickListener { launchMorf() }

        updateStatus(Status.IDLE)
    }

    private fun startProcess() {
        // WireGuard 설치 확인
        if (packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE) == null) {
            showToast("WireGuard 설치 페이지로 이동합니다.")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WIREGUARD_PACKAGE")))
            return
        }

        isConnecting = true
        updateStatus(Status.CONNECTING)

        // WireGuard 실행
        val intent = packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        // VPN 연결 감지 시작
        startVpnMonitoring()
    }

    private fun startVpnMonitoring() {
        var elapsed = 0L
        vpnCheckRunnable = object : Runnable {
            override fun run() {
                if (!isConnecting) return
                elapsed += VPN_CHECK_INTERVAL_MS
                when {
                    isVpnActive() -> {
                        stopMonitoring()
                        isConnecting = false
                        updateStatus(Status.VPN_CONNECTED)
                        handler.postDelayed({
                            if (launchMorf()) {
                                updateStatus(Status.MORF_LAUNCHED)
                                MorfWatcherService.start(this@MainActivity)
                            } else {
                                showToast("모프가 설치되어 있지 않습니다.")
                                updateStatus(Status.IDLE)
                            }
                        }, 1500L)
                    }
                    elapsed >= VPN_TIMEOUT_MS -> {
                        stopMonitoring()
                        isConnecting = false
                        updateStatus(Status.ERROR, "WireGuard 연결 실패.\nWireGuard 앱에서 직접 연결해주세요.")
                    }
                    else -> {
                        val remaining = (VPN_TIMEOUT_MS - elapsed) / 1000
                        binding.tvStatus.text = "🔄 WireGuard 연결 대기 중... (${remaining}초)"
                        handler.postDelayed(this, VPN_CHECK_INTERVAL_MS)
                    }
                }
            }
        }
        handler.postDelayed(vpnCheckRunnable!!, VPN_CHECK_INTERVAL_MS)
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    private fun cancelConnection() {
        stopMonitoring()
        isConnecting = false
        updateStatus(Status.IDLE)
        showToast("연결이 취소되었습니다.")
    }

    private fun openWireGuard() {
        val intent = packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)
        if (intent != null) startActivity(intent)
        else {
            showToast("WireGuard 설치 페이지로 이동합니다.")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WIREGUARD_PACKAGE")))
        }
    }

    private fun launchMorf(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(MORF_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun disconnectWireGuard() {
        // WireGuard 앱 실행 (자동 해제는 WireGuard 자체에서 처리)
        try {
            val intent = packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
        } catch (_: Exception) {}
    }

    private fun stopMonitoring() {
        vpnCheckRunnable?.let { handler.removeCallbacks(it) }
        vpnCheckRunnable = null
    }

    private fun updateStatus(status: Status, message: String? = null) {
        runOnUiThread {
            when (status) {
                Status.IDLE -> {
                    binding.statusIcon.text = "⏸"
                    binding.tvStatus.text = "시작 버튼을 눌러주세요"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = "▶  연결 시작"
                    binding.btnCancel.visibility = android.view.View.GONE
                }
                Status.CONNECTING -> {
                    binding.statusIcon.text = "🔄"
                    binding.tvStatus.text = "WireGuard 연결 중..."
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.btnCancel.visibility = android.view.View.VISIBLE
                }
                Status.VPN_CONNECTED -> {
                    binding.statusIcon.text = "🔒"
                    binding.tvStatus.text = "VPN 연결 완료!\n모프 실행 중..."
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.btnCancel.visibility = android.view.View.GONE
                }
                Status.MORF_LAUNCHED -> {
                    binding.statusIcon.text = "✅"
                    binding.tvStatus.text = "완료!\n모프 종료 시 WireGuard 자동 해제"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnCancel.visibility = android.view.View.GONE
                    binding.btnStart.text = "↺  다시 시작"
                }
                Status.ERROR -> {
                    binding.statusIcon.text = "❌"
                    binding.tvStatus.text = message ?: "오류가 발생했습니다."
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnCancel.visibility = android.view.View.GONE
                    binding.btnStart.text = "↺  다시 시도"
                }
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        unregisterReceiver(morfStoppedReceiver)
    }

    enum class Status { IDLE, CONNECTING, VPN_CONNECTED, MORF_LAUNCHED, ERROR }
}
