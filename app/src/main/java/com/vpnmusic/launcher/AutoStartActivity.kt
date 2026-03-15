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
    private var checkedServers = mutableListOf<VpnGateManager.VpnServer>()
    private var currentServerIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoStartBinding.inflate(layoutInflater)
        setContentView(binding.root)
        startAutoProcess()
    }

    private fun startAutoProcess() {
        checkedServers = VpnGateManager.getCheckedServers(this).toMutableList()

        if (checkedServers.isNotEmpty()) {
            // 체크된 서버로 연결
            currentServerIndex = 0
            binding.tvStatus.text = "저장된 서버로 연결 중..."
            binding.statusIcon.text = "🔄"
            connectNextServer()
        } else {
            // 저장된 서버 없으면 자동 검색
            binding.tvStatus.text = "🌐 빠른 서버 검색 중..."
            binding.statusIcon.text = "🔍"
            lifecycleScope.launch {
                val best = VpnGateManager.getBestServer(this@AutoStartActivity)
                if (best == null) {
                    showError("서버를 찾지 못했습니다.")
                    return@launch
                }
                val ovpnFile = VpnGateManager.saveOvpnFile(this@AutoStartActivity, best)
                if (ovpnFile == null) {
                    showError("파일 생성 실패.")
                    return@launch
                }
                pendingOvpnPath = ovpnFile.absolutePath
                handler.post { requestVpnPermission() }
            }
        }
    }

    private fun connectNextServer() {
        if (currentServerIndex >= checkedServers.size) {
            showError("모든 서버 연결 실패.\nYTmusic 앱에서 다른 서버를 선택해주세요.")
            return
        }
        val server = checkedServers[currentServerIndex]
        binding.tvStatus.text = "서버 연결 중... (${currentServerIndex + 1}/${checkedServers.size})\n${server.ip}"

        val ovpnFile = VpnGateManager.saveOvpnFile(this, server)
        if (ovpnFile == null) {
            currentServerIndex++
            connectNextServer()
            return
        }
        pendingOvpnPath = ovpnFile.absolutePath
        requestVpnPermission()
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
            showError("OpenVPN Connect를 설치해주세요.")
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
                                finish()
                            } else {
                                showError("모프가 설치되어 있지 않습니다.")
                            }
                        }, 1500L)
                    }
                    elapsed >= VpnHelper.VPN_TIMEOUT_MS -> {
                        stopMonitoring()
                        // 다음 서버 시도
                        if (checkedServers.isNotEmpty() && currentServerIndex < checkedServers.size - 1) {
                            currentServerIndex++
                            Toast.makeText(this@AutoStartActivity, "연결 실패. 다음 서버 시도...", Toast.LENGTH_SHORT).show()
                            connectNextServer()
                        } else {
                            showError("VPN 연결 실패.\nYTmusic 앱에서 다른 서버를 선택해주세요.")
                        }
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
