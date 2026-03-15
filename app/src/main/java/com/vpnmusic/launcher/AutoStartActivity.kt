package com.vpnmusic.launcher

import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vpnmusic.launcher.databinding.ActivityAutoStartBinding
import kotlinx.coroutines.launch

class AutoStartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoStartBinding
    private val handler = Handler(Looper.getMainLooper())
    private var vpnCheckRunnable: Runnable? = null
    private var pendingOvpnPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 실행 즉시 자동 시작
        startAutoProcess()
    }

    private fun startAutoProcess() {
        binding.tvStatus.text = "🌐 빠른 서버 검색 중..."
        binding.statusIcon.text = "🔍"

        lifecycleScope.launch {
            val best = VpnGateManager.getBestServer(this@AutoStartActivity)
            if (best == null) {
                showError("서버를 찾지 못했습니다.\n인터넷 연결을 확인해주세요.")
                return@launch
            }

            val speedStr = VpnGateManager.formatSpeed(best.speed)
            binding.tvStatus.text = "✅ 서버 발견!\n${best.ip} (${speedStr})"

            val ovpnFile = VpnGateManager.saveOvpnFile(this@AutoStartActivity, best)
            if (ovpnFile == null) {
                showError("파일 생성 실패.")
                return@launch
            }

            pendingOvpnPath = ovpnFile.absolutePath
            handler.post { requestVpnPermission() }
        }
    }

    private fun requestVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQ_VPN)
        } else {
            connectVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            connectVpn()
        } else {
            showError("VPN 권한이 거부되었습니다.")
        }
    }

    private fun connectVpn() {
        val path = pendingOvpnPath ?: return
        binding.tvStatus.text = "🔄 VPN 연결 중..."
        binding.statusIcon.text = "🔄"

        val ok = VpnHelper.launchOpenVpnWithProfile(this, path)
        if (!ok) {
            showError("OpenVPN for Android를 설치해주세요.")
            return
        }
        startVpnMonitoring()
    }

    private fun startVpnMonitoring() {
        var elapsed = 0L
        vpnCheckRunnable = object : Runnable {
            override fun run() {
                elapsed += VpnHelper.VPN_CHECK_INTERVAL_MS
                when {
                    VpnHelper.isVpnActive(this@AutoStartActivity) -> {
                        stopMonitoring()
                        binding.tvStatus.text = "🔒 VPN 연결 완료!\n모프 실행 중..."
                        binding.statusIcon.text = "🔒"
                        handler.postDelayed({
                            val ok = VpnHelper.launchMorf(this@AutoStartActivity)
                            if (ok) {
                                YoutubeMusicWatcherService.start(this@AutoStartActivity)
                                finish() // 모프 실행되면 이 화면 닫기
                            } else {
                                showError("모프가 설치되어 있지 않습니다.")
                            }
                        }, 1500L)
                    }
                    elapsed >= VpnHelper.VPN_TIMEOUT_MS -> {
                        stopMonitoring()
                        showError("VPN 연결 시간 초과.")
                    }
                    else -> {
                        val remaining = (VpnHelper.VPN_TIMEOUT_MS - elapsed) / 1000
                        binding.tvStatus.text = "🔄 VPN 연결 대기 중... (${remaining}초)"
                        handler.postDelayed(this, VpnHelper.VPN_CHECK_INTERVAL_MS)
                    }
                }
            }
        }
        handler.postDelayed(vpnCheckRunnable!!, VpnHelper.VPN_CHECK_INTERVAL_MS)
    }

    private fun stopMonitoring() {
        vpnCheckRunnable?.let { handler.removeCallbacks(it) }
        vpnCheckRunnable = null
    }

    private fun showError(msg: String) {
        binding.tvStatus.text = msg
        binding.statusIcon.text = "❌"
        binding.progressBar.visibility = android.view.View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }

    companion object {
        private const val REQ_VPN = 1002
    }
}
