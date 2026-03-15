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
    private var checkedServers = mutableListOf<VpnGateManager.VpnServer>()
    private var currentServerIndex = 0
    private var isAutoSearch = false

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

        binding.btnStart.setOnClickListener {
            isAutoSearch = false
            startProcess()
        }
        binding.btnAutoSearch.setOnClickListener {
            isAutoSearch = true
            startProcess()
        }
        binding.btnServerList.setOnClickListener {
            startActivity(Intent(this, ServerListActivity::class.java))
        }
        binding.btnAutoShortcut.setOnClickListener { createAutoShortcut() }
        binding.btnOpenVpn.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(VpnHelper.OPENVPN_PACKAGE)
            if (intent != null) startActivity(intent)
            else showToast("OpenVPN Connect가 설치되어 있지 않습니다.")
        }
        binding.btnYoutubeMusic.setOnClickListener {
            val ok = VpnHelper.launchMorf(this)
            if (!ok) showToast("모프가 설치되어 있지 않습니다.")
        }

        updateStatus(AppStatus.IDLE)
    }

    override fun onResume() {
        super.onResume()
        // 서버 목록 화면에서 돌아올 때 체크된 서버 갱신
        checkedServers = VpnGateManager.getCheckedServers(this).toMutableList()
    }

    private fun startProcess() {
        currentServerIndex = 0
        if (isAutoSearch) {
            updateStatus(AppStatus.FETCHING_SERVER)
            lifecycleScope.launch {
                binding.tvStatus.text = "🌐 빠른 서버 자동 검색 중..."
                val best = VpnGateManager.getBestServer(this@MainActivity)
                if (best == null) {
                    updateStatus(AppStatus.ERROR, "서버를 찾지 못했습니다.\n인터넷 연결을 확인해주세요.")
                    return@launch
                }
                val speedStr = VpnGateManager.formatSpeed(best.speed)
                val ovpnFile = VpnGateManager.saveOvpnFile(this@MainActivity, best)
                if (ovpnFile == null) {
                    updateStatus(AppStatus.ERROR, "파일 생성 실패.")
                    return@launch
                }
                handler.post { requestVpnPermission(ovpnFile.absolutePath, best.ip, speedStr, best) }
            }
        } else {
            checkedServers = VpnGateManager.getCheckedServers(this).toMutableList()
            if (checkedServers.isEmpty()) {
                updateStatus(AppStatus.ERROR, "서버 목록에서 서버를 선택해주세요.\n📋 서버 목록 버튼을 눌러주세요.")
                return
            }
            connectNextServer()
        }
    }

    private fun connectNextServer() {
        if (currentServerIndex >= checkedServers.size) {
            updateStatus(AppStatus.ERROR, "체크된 서버 모두 연결 실패.\n🔄 자동 검색 버튼을 눌러보세요.")
            return
        }
        val server = checkedServers[currentServerIndex]
        val speedStr = VpnGateManager.formatSpeed(server.speed)
        binding.tvStatus.text = "서버 연결 시도 중... (${currentServerIndex + 1}/${checkedServers.size})\n${server.ip}"
        binding.statusIcon.text = "🔄"
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnStart.isEnabled = false

        val ovpnFile = VpnGateManager.saveOvpnFile(this, server)
        if (ovpnFile == null) {
            currentServerIndex++
            connectNextServer()
            return
        }
        requestVpnPermission(ovpnFile.absolutePath, server.ip, speedStr, server)
    }

    private fun requestVpnPermission(ovpnPath: String, ip: String, speed: String, server: VpnGateManager.VpnServer) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingOvpnPath = ovpnPath
            pendingIp = ip
            pendingSpeed = speed
            startActivityForResult(vpnIntent, REQ_VPN)
        } else {
            connectVpn(ovpnPath, ip, speed, server)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            val server = if (currentServerIndex < checkedServers.size) checkedServers[currentServerIndex] else null
            connectVpn(pendingOvpnPath ?: "", pendingIp ?: "", pendingSpeed ?: "", server)
        } else if (requestCode == REQ_VPN) {
            updateStatus(AppStatus.ERROR, "VPN 권한이 거부되었습니다.")
        }
    }

    private fun connectVpn(ovpnPath: String, ip: String, speed: String, server: VpnGateManager.VpnServer?) {
        val ok = VpnHelper.launchOpenVpnWithProfile(this, ovpnPath)
        if (!ok) {
            updateStatus(AppStatus.ERROR, "OpenVPN Connect를 설치해주세요.")
            return
        }
        updateStatus(AppStatus.CONNECTING_VPN, ip, speed)
        startVpnMonitoring(server)
    }

    private fun startVpnMonitoring(server: VpnGateManager.VpnServer?) {
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
                        // 다음 서버 시도
                        if (!isAutoSearch && currentServerIndex < checkedServers.size - 1) {
                            currentServerIndex++
                            showToast("연결 실패. 다음 서버 시도 중...")
                            connectNextServer()
                        } else {
                            updateStatus(AppStatus.ERROR, "VPN 연결 실패.\n다른 서버를 선택하거나\n🔄 자동 검색을 눌러주세요.")
                        }
                    }
                    else -> {
                        val remaining = (VpnHelper.VPN_TIMEOUT_MS - elapsed) / 1000
                        binding.tvStatus.text = "🔄 VPN 연결 대기 중... (${remaining}초)\n${server?.ip ?: ""}"
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
                    binding.btnAutoSearch.isEnabled = true
                    binding.btnStart.text = "▶  저장된 서버로 연결"
                }
                AppStatus.FETCHING_SERVER -> {
                    binding.tvStatus.text = "서버 검색 중..."
                    binding.statusIcon.text = "🔍"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.btnAutoSearch.isEnabled = false
                }
                AppStatus.CONNECTING_VPN -> {
                    binding.tvStatus.text = "VPN 연결 중...\n$ip ($speed)"
                    binding.statusIcon.text = "🔄"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.btnAutoSearch.isEnabled = false
                }
                AppStatus.VPN_CONNECTED -> {
                    binding.tvStatus.text = "VPN 연결 완료!\n모프 실행 중..."
                    binding.statusIcon.text = "🔒"
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.btnAutoSearch.isEnabled = false
                }
                AppStatus.MORF_LAUNCHED -> {
                    binding.tvStatus.text = "완료!\n모프 종료 시 VPN도 자동 해제됩니다."
                    binding.statusIcon.text = "✅"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnAutoSearch.isEnabled = true
                    binding.btnStart.text = "↺  다시 시작"
                }
                AppStatus.ERROR -> {
                    binding.tvStatus.text = message ?: "오류가 발생했습니다."
                    binding.statusIcon.text = "❌"
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnStart.isEnabled = true
                    binding.btnAutoSearch.isEnabled = true
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
