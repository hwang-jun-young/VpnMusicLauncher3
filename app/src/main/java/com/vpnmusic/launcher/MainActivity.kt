package com.vpnmusic.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vpnmusic.launcher.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var vpnCheckRunnable: Runnable? = null
    private var pendingOvpnPath: String? = null
    private var pendingIp: String? = null
    private var pendingSpeed: String? = null

    private val vpnDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == YoutubeMusicWatcherService.ACTION_VPN_DISCONNECTED) {
                updateStatus(AppStatus.IDLE)
                showToast("모프 종료 → VPN 해제됨")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(
            vpnDisconnectedReceiver,
            IntentFilter(YoutubeMusicWatcherService.ACTION_VPN_DISCONNECTED),
            RECEIVER_NOT_EXPORTED
        )

        binding.btnStart.setOnClickListener { startProcess() }
        binding.btnAutoShortcut.setOnClickListener { createAutoShortcut() }
        binding.btnOpenVpn.setOnClickListener {
            // OpenVPN 앱 실행
            val intent = packageManager.getLaunchIntentForPackage(VpnHelper.OPENVPN_PACKAGE)
            if (intent != null) startActivity(intent)
            else showToast("OpenVPN이 설치되어 있지 않습니다.")
        }
        binding.btnYoutubeMusic.setOnClickListener {
            // 모프 실행
            val ok = VpnHelper.launchMorf(this)
            if (!ok) showToast("모프가 설치되어 있지 않습니다.")
        }

        updateStatus(AppStatus.IDLE)
    }

    private fun startProcess() {
        updateStatus(AppStatus.FETCHING_SERVER)
        lifecycleScope.launch {
            binding.tvStatus.text = "🌐 빠른 서버 검색 중..."
            val best = VpnGateManager.getBestServer(this@MainActivity)
            if (best == null) {
                updateStatus(AppStatus.ERROR, "서버를 찾지 못했습니다.\n인터넷 연결을 확인해주세요.")
                return@launch
            }
            val speedStr = VpnGateManager.formatSpeed(best.speed)
            binding.tvStatus.text = "✅ 서버 발견! ${best.ip} (${speedStr})"
            val ovpnFile = VpnGateManager.saveOvpnFile(this@MainActivity, best)
            if (ovpnFile == null) {
                updateStatus(AppStatus.ERROR, "파일 생성 실패.")
                return@launch
            }
            handler.post { requestVpnPermission(ovpnFile.absolutePath, best.ip, speedStr) }
        }
    }

    private fun requestVpnPermission(ovpnPath: String, ip: String, speed: String) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingOvpnPath = ovpnPath
            pendingIp = ip
            pendingSpeed = speed
            startActivityForResult(vpnIntent, REQ_VPN)
        } else {
            connectVpn(ovpnPath, ip, speed)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            connectVpn(pendingOvpnPath ?: "", pendingIp ?: "", pendingSpeed ?: "")
        } else if (requestCode == REQ_VPN) {
            updateStatus(AppStatus.ERROR, "VPN 권한이 거부되었습니다.")
        }
    }

    private fun connectVpn(ovpnPath: String, ip: String, speed: String) {
        val ok = VpnHelper.launchOpenVpnWithProfile(this, ovpnPath)
        if (!ok) {
            updateStatus(AppStatus.ERROR, "OpenVPN for Android를 설치해주세요.\n(개발자: Arne Schwabe)")
            return
        }
        updateStatus(AppStatus.CONNECTING_VPN, ip, speed)
        startVpnMonitoring()
    }

    private fun startVpnMonitoring() {
        var elapsed = 0L
        vpnCheckRunnable = object : Runnable {
            override fun run() {
                elapsed += VpnHelper.VPN_CHECK_INTERVAL_MS
                when {
                    VpnHelper.isVpnActive(this@MainActivity) -> {
                        stopMonitoring()
                        updateStatus(AppStatus.VPN_CONNECTED)
                        handler.postDelayed({
                            val ok = VpnHelper.launchMorf(this@MainActivity)
                            if (ok) {
                                updateStatus(AppStatus.MORF_LAUNCHED)
                                YoutubeMusicWatcherService.start(this@MainActivity)
                            } else {
                                updateStatus(AppStatus.ERROR, "모프가 설치되어 있지 않습니다.")
                            }
                        }, 1500L)
                    }
                    elapsed >= VpnHelper.VPN_TIMEOUT_MS -> {
                        stopMonitoring()
                        updateStatus(AppStatus.ERROR, "VPN 연결 시간 초과.\nOpenVPN 앱에서 수동 연결 후\n모프 버튼을 눌러주세요.")
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

    private fun createAutoShortcut() {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm.isRequestPinShortcutSupported) {
            val shortcut = ShortcutInfo.Builder(this, "auto_start")
                .setShortLabel("YTmusic 자동연결")
                .setLongLabel("YTmusic 자동연결")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, AutoStartActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                })
                .build()
            sm.requestPinShortcut(shortcut, null)
            showToast("바탕화면에 자동연결 아이콘이 추가됩니다!")
        } else {
            showToast("이 기기에서는 바로가기 추가가 지원되지 않습니다.")
        }
    }

    private fun updateStatus(status: AppStatus, ip: String = "", speed: String = "", message: String? = null) {
        runOnUiThread {
            when (status) {
                AppStatus.IDLE -> {
                    binding.tvStatus.text = "시작 버튼을 눌러주세요"
                    binding.statusIcon.text = "⏸"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = "▶  VPN + 모프 시작"
                }
                AppStatus.FETCHING_SERVER -> {
                    binding.tvStatus.text = "서버 검색 중..."
                    binding.statusIcon.text = "🔍"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                }
                AppStatus.CONNECTING_VPN -> {
                    binding.tvStatus.text = "VPN 연결 중...\n$ip ($speed)"
                    binding.statusIcon.text = "🔄"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                }
                AppStatus.VPN_CONNECTED -> {
                    binding.tvStatus.text = "VPN 연결 완료!\n모프 실행 중..."
                    binding.statusIcon.text = "🔒"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                }
                AppStatus.MORF_LAUNCHED -> {
                    binding.tvStatus.text = "완료!\n모프 종료 시 VPN도 자동 해제됩니다."
                    binding.statusIcon.text = "✅"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = "↺  다시 시작"
                }
                AppStatus.ERROR -> {
                    binding.tvStatus.text = message ?: "오류가 발생했습니다."
                    binding.statusIcon.text = "❌"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = "↺  다시 시도"
                }
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        unregisterReceiver(vpnDisconnectedReceiver)
    }

    enum class AppStatus { IDLE, FETCHING_SERVER, CONNECTING_VPN, VPN_CONNECTED, MORF_LAUNCHED, ERROR }

    companion object {
        private const val REQ_VPN = 1001
    }
}
