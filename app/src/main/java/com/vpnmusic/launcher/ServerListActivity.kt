package com.vpnmusic.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vpnmusic.launcher.databinding.ActivityServerListBinding
import kotlinx.coroutines.launch

class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerListBinding
    private var servers = mutableListOf<VpnGateManager.VpnServer>()
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        servers = VpnGateManager.loadSavedServers(this)
        adapter = ServerAdapter(servers) { saveServers() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // VPN Gate 서버 목록 불러오기
        binding.btnLoadVpnGate.setOnClickListener {
            loadVpnGateServers()
        }

        // 수동 입력
        binding.btnAddManual.setOnClickListener {
            showManualInputDialog()
        }

        // 뒤로가기
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadVpnGateServers() {
        binding.btnLoadVpnGate.isEnabled = false
        binding.btnLoadVpnGate.text = "로딩 중..."

        lifecycleScope.launch {
            val vpnGateServers = VpnGateManager.fetchFastestServers(this@ServerListActivity)
            if (vpnGateServers.isEmpty()) {
                Toast.makeText(this@ServerListActivity, "서버 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                binding.btnLoadVpnGate.isEnabled = true
                binding.btnLoadVpnGate.text = "🌐 VPN Gate 서버 목록 불러오기"
                return@launch
            }

            // 기존 저장 서버와 중복 제거 후 추가
            val existingIps = servers.map { it.ip }.toSet()
            val newServers = vpnGateServers.filter { it.ip !in existingIps }
            servers.addAll(newServers)
            adapter.notifyDataSetChanged()
            saveServers()

            Toast.makeText(this@ServerListActivity, "${newServers.size}개 서버 추가됨!", Toast.LENGTH_SHORT).show()
            binding.btnLoadVpnGate.isEnabled = true
            binding.btnLoadVpnGate.text = "🌐 VPN Gate 서버 목록 불러오기"
        }
    }

    private fun showManualInputDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val input = EditText(this).apply {
            hint = "IP 또는 호스트명 입력 (예: 123.456.789.0)"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("서버 수동 입력")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotBlank()) {
                    val newServer = VpnGateManager.VpnServer(
                        hostname = ip,
                        ip = ip,
                        score = 0,
                        ping = 0,
                        speed = 0,
                        ovpnBase64 = "",
                        isChecked = true
                    )
                    servers.add(0, newServer)
                    adapter.notifyItemInserted(0)
                    saveServers()
                    Toast.makeText(this, "서버 추가됨: $ip", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveServers() {
        VpnGateManager.saveSavedServers(this, servers)
    }

    inner class ServerAdapter(
        private val list: MutableList<VpnGateManager.VpnServer>,
        private val onChanged: () -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(android.R.id.text1) as? CheckBox
                ?: CheckBox(view.context)
            val tvIp: TextView = TextView(view.context)
            val tvSpeed: TextView = TextView(view.context)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val server = list[position]
            val itemView = holder.itemView

            val checkBox = itemView.findViewById<CheckBox>(R.id.checkBox)
            val tvIp = itemView.findViewById<TextView>(R.id.tvIp)
            val tvSpeed = itemView.findViewById<TextView>(R.id.tvSpeed)
            val btnDelete = itemView.findViewById<View>(R.id.btnDelete)

            checkBox.isChecked = server.isChecked
            tvIp.text = "${server.hostname} (${server.ip})"
            tvSpeed.text = if (server.speed > 0) VpnGateManager.formatSpeed(server.speed) else "수동 입력"

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                list[position].isChecked = isChecked
                onChanged()
            }

            btnDelete.setOnClickListener {
                list.removeAt(position)
                notifyItemRemoved(position)
                onChanged()
            }
        }

        override fun getItemCount() = list.size
    }
}
