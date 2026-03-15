package com.vpnmusic.launcher

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
    private var allServers = mutableListOf<VpnGateManager.VpnServer>()
    private var filteredServers = mutableListOf<VpnGateManager.VpnServer>()
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        allServers = VpnGateManager.loadSavedServers(this)
        filteredServers = allServers.toMutableList()
        adapter = ServerAdapter(filteredServers) { saveServers() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // IP 검색
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterServers(s?.toString() ?: "")
            }
        })

        // VPN Gate 서버 목록 불러오기
        binding.btnLoadVpnGate.setOnClickListener { loadVpnGateServers() }

        // 전체 선택/해제
        binding.btnSelectAll.setOnClickListener { selectAll() }

        // 선택 삭제
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }

        // 저장 (체크된 서버만 내 목록에 저장)
        binding.btnSave.setOnClickListener { saveMyServers() }

        // 불러오기 (저장된 내 목록 불러오기)
        binding.btnLoad.setOnClickListener { loadMyServers() }

        // 뒤로가기
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun filterServers(query: String) {
        filteredServers.clear()
        if (query.isBlank()) {
            filteredServers.addAll(allServers)
        } else {
            filteredServers.addAll(allServers.filter {
                it.ip.contains(query, ignoreCase = true) ||
                it.hostname.contains(query, ignoreCase = true) ||
                it.countryLong.contains(query, ignoreCase = true) ||
                it.countryShort.contains(query, ignoreCase = true)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadVpnGateServers() {
        binding.btnLoadVpnGate.isEnabled = false
        binding.btnLoadVpnGate.text = "로딩 중..."

        lifecycleScope.launch {
            val vpnGateServers = VpnGateManager.fetchFastestServers(this@ServerListActivity)
            if (vpnGateServers.isEmpty()) {
                Toast.makeText(this@ServerListActivity, "서버 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                binding.btnLoadVpnGate.isEnabled = true
                binding.btnLoadVpnGate.text = "🌐 VPN Gate 목록"
                return@launch
            }

            val existingIps = allServers.map { it.ip }.toSet()
            val newServers = vpnGateServers.filter { it.ip !in existingIps }
            allServers.addAll(newServers)
            // 체크된 서버 위로 정렬
            allServers.sortWith(compareByDescending { it.isChecked })
            filteredServers.clear()
            filteredServers.addAll(allServers)
            adapter.notifyDataSetChanged()
            saveServers()

            Toast.makeText(this@ServerListActivity, "${newServers.size}개 서버 추가됨!", Toast.LENGTH_SHORT).show()
            binding.btnLoadVpnGate.isEnabled = true
            binding.btnLoadVpnGate.text = "🌐 VPN Gate 목록"
        }
    }

    private fun selectAll() {
        val allChecked = filteredServers.all { it.isChecked }
        filteredServers.forEach { it.isChecked = !allChecked }
        // allServers도 동기화
        val filteredIps = filteredServers.map { it.ip }.toSet()
        allServers.forEach { if (it.ip in filteredIps) it.isChecked = !allChecked }
        adapter.notifyDataSetChanged()
        saveServers()
        showToast(if (!allChecked) "전체 선택됨" else "전체 해제됨")
    }

    private fun deleteSelected() {
        val checkedCount = filteredServers.count { it.isChecked }
        if (checkedCount == 0) {
            showToast("선택된 서버가 없습니다.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("선택 삭제")
            .setMessage("선택된 ${checkedCount}개 서버를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                val checkedIps = filteredServers.filter { it.isChecked }.map { it.ip }.toSet()
                allServers.removeAll { it.ip in checkedIps }
                filteredServers.removeAll { it.ip in checkedIps }
                adapter.notifyDataSetChanged()
                saveServers()
                showToast("${checkedCount}개 서버 삭제됨")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveMyServers() {
        val checkedCount = allServers.count { it.isChecked }
        if (checkedCount == 0) {
            showToast("체크된 서버가 없습니다.")
            return
        }
        VpnGateManager.saveMyServers(this, allServers)
        showToast("${checkedCount}개 서버가 내 목록에 저장됐습니다!")
    }

    private fun loadMyServers() {
        val myServers = VpnGateManager.loadMyServers(this)
        if (myServers.isEmpty()) {
            showToast("저장된 내 목록이 없습니다.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("내 목록 불러오기")
            .setMessage("저장된 ${myServers.size}개 서버를 불러올까요?\n현재 체크 상태가 변경됩니다.")
            .setPositiveButton("불러오기") { _, _ ->
                val myIps = myServers.map { it.ip }.toSet()
                // 기존 체크 해제
                allServers.forEach { it.isChecked = false }
                // 내 목록에 있는 서버 체크
                allServers.forEach { if (it.ip in myIps) it.isChecked = true }
                // 내 목록에 없는 서버 추가
                val existingIps = allServers.map { it.ip }.toSet()
                myServers.filter { it.ip !in existingIps }.forEach { allServers.add(it) }
                // 체크된 서버 위로 정렬
                allServers.sortWith(compareByDescending { it.isChecked })
                filteredServers.clear()
                filteredServers.addAll(allServers)
                adapter.notifyDataSetChanged()
                saveServers()
                showToast("내 목록 불러오기 완료!")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveServers() {
        VpnGateManager.saveSavedServers(this, allServers)
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class ServerAdapter(
        private val list: MutableList<VpnGateManager.VpnServer>,
        private val onChanged: () -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.checkBox)
            val tvIp: TextView = view.findViewById(R.id.tvIp)
            val tvSpeed: TextView = view.findViewById(R.id.tvSpeed)
            val btnDelete: View = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val server = list[position]

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = server.isChecked
            holder.tvIp.text = "[${server.countryShort}] ${server.hostname} (${server.ip})"
            holder.tvSpeed.text = if (server.speed > 0) VpnGateManager.formatSpeed(server.speed) else "속도 정보 없음"

            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) {
                    list[pos].isChecked = isChecked
                    // allServers도 동기화
                    allServers.find { it.ip == list[pos].ip }?.isChecked = isChecked
                    onChanged()
                }
            }

            holder.btnDelete.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) {
                    val ip = list[pos].ip
                    allServers.removeAll { it.ip == ip }
                    list.removeAt(pos)
                    notifyItemRemoved(pos)
                    onChanged()
                }
            }
        }

        override fun getItemCount() = list.size
    }
}
