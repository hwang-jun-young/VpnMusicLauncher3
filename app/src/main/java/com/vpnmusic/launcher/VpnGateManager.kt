package com.vpnmusic.launcher

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URL

object VpnGateManager {

    private val VPNGATE_APIS = listOf(
        "https://www.vpngate.net/api/iphone/",
        "https://raw.githubusercontent.com/sinspired/VpngateAPI/main/servers.csv"
    )

    private const val CACHE_FILE = "vpngate_cache.json"
    private const val SAVED_SERVERS_FILE = "saved_servers.json"
    private const val MY_SERVERS_FILE = "my_servers.json"
    private const val CACHE_EXPIRE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PING_TIMEOUT_MS = 2000L
    private const val API_TIMEOUT_MS = 10000L
    private const val TOP_SERVERS_TO_PING = 10
    private val EXCLUDED_COUNTRIES = setOf("KR")

    data class VpnServer(
        val hostname: String,
        val ip: String,
        val score: Long,
        val ping: Int,
        val speed: Long,
        val ovpnBase64: String,
        var isChecked: Boolean = false,
        val countryShort: String = "",
        val countryLong: String = ""
    )

    // 전체 서버 목록 불러오기
    fun loadSavedServers(context: Context): MutableList<VpnServer> {
        return try {
            val file = File(context.filesDir, SAVED_SERVERS_FILE)
            if (!file.exists()) return mutableListOf()
            val arr = JSONArray(file.readText())
            val servers = mutableListOf<VpnServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                servers.add(VpnServer(
                    hostname = obj.getString("hostname"),
                    ip = obj.getString("ip"),
                    score = obj.optLong("score", 0),
                    ping = obj.optInt("ping", 9999),
                    speed = obj.optLong("speed", 0),
                    ovpnBase64 = obj.optString("ovpn", ""),
                    isChecked = obj.optBoolean("checked", false),
                    countryShort = obj.optString("countryShort", ""),
                    countryLong = obj.optString("countryLong", "")
                ))
            }
            // 체크된 서버가 위로 정렬
            servers.sortWith(compareByDescending { it.isChecked })
            servers
        } catch (_: Exception) { mutableListOf() }
    }

    // 전체 서버 목록 저장
    fun saveSavedServers(context: Context, servers: List<VpnServer>) {
        try {
            val arr = JSONArray()
            servers.forEach { s ->
                arr.put(JSONObject().apply {
                    put("hostname", s.hostname)
                    put("ip", s.ip)
                    put("score", s.score)
                    put("ping", s.ping)
                    put("speed", s.speed)
                    put("ovpn", s.ovpnBase64)
                    put("checked", s.isChecked)
                    put("countryShort", s.countryShort)
                    put("countryLong", s.countryLong)
                })
            }
            File(context.filesDir, SAVED_SERVERS_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    // 내 서버 목록 저장 (체크된 서버만)
    fun saveMyServers(context: Context, servers: List<VpnServer>) {
        try {
            val checked = servers.filter { it.isChecked }
            val arr = JSONArray()
            checked.forEach { s ->
                arr.put(JSONObject().apply {
                    put("hostname", s.hostname)
                    put("ip", s.ip)
                    put("score", s.score)
                    put("ping", s.ping)
                    put("speed", s.speed)
                    put("ovpn", s.ovpnBase64)
                    put("checked", true)
                    put("countryShort", s.countryShort)
                    put("countryLong", s.countryLong)
                })
            }
            File(context.filesDir, MY_SERVERS_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    // 내 서버 목록 불러오기
    fun loadMyServers(context: Context): MutableList<VpnServer> {
        return try {
            val file = File(context.filesDir, MY_SERVERS_FILE)
            if (!file.exists()) return mutableListOf()
            val arr = JSONArray(file.readText())
            val servers = mutableListOf<VpnServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                servers.add(VpnServer(
                    hostname = obj.getString("hostname"),
                    ip = obj.getString("ip"),
                    score = obj.optLong("score", 0),
                    ping = obj.optInt("ping", 9999),
                    speed = obj.optLong("speed", 0),
                    ovpnBase64 = obj.optString("ovpn", ""),
                    isChecked = true,
                    countryShort = obj.optString("countryShort", ""),
                    countryLong = obj.optString("countryLong", "")
                ))
            }
            servers
        } catch (_: Exception) { mutableListOf() }
    }

    // 체크된 서버 목록
    fun getCheckedServers(context: Context): List<VpnServer> {
        return loadSavedServers(context).filter { it.isChecked }
    }

    // 새 서버 자동 검색 (핑 병렬 테스트)
    suspend fun getBestServer(context: Context): VpnServer? = withContext(Dispatchers.IO) {
        val servers = fetchFastestServers(context)
        if (servers.isEmpty()) return@withContext null

        // 최소 속도 10Mbps 이상 필터
        val candidates = servers.filter { it.speed >= 10_000_000 }
            .ifEmpty { servers }

        // 상위 10개 핑 테스트 병렬 실행
        val topServers = candidates.take(TOP_SERVERS_TO_PING)
        val pingResults = coroutineScope {
            topServers.map { server ->
                async { Pair(server, measurePing(server.ip)) }
            }.awaitAll()
        }

        // 핑 응답하는 서버 중 점수 계산 (핑 낮을수록 + score 높을수록 좋음)
        pingResults
            .filter { it.second != -1 }
            .minByOrNull { (server, ping) ->
                ping - (server.score / 100000).toInt()
            }
            ?.first
            ?: candidates.firstOrNull()
            ?: servers.firstOrNull()
    }

    private suspend fun measurePing(ip: String): Int = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val reachable = withTimeoutOrNull(PING_TIMEOUT_MS) {
                InetAddress.getByName(ip).isReachable(PING_TIMEOUT_MS.toInt())
            } ?: false
            if (reachable) (System.currentTimeMillis() - start).toInt() else -1
        } catch (_: Exception) { -1 }
    }

    suspend fun fetchFastestServers(context: Context): List<VpnServer> = withContext(Dispatchers.IO) {
        val cached = loadCache(context)
        if (cached.isNotEmpty()) return@withContext cached

        for (api in VPNGATE_APIS) {
            try {
                val csv = withTimeoutOrNull(API_TIMEOUT_MS) {
                    URL(api).readText()
                } ?: continue
                if (csv.isBlank()) continue
                val servers = parseServers(csv).filter { it.speed > 0 }.sortedByDescending { it.speed }
                if (servers.isNotEmpty()) {
                    saveCache(context, servers)
                    return@withContext servers
                }
            } catch (_: Exception) { continue }
        }
        emptyList()
    }

    private fun loadCache(context: Context): List<VpnServer> {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            val json = JSONObject(file.readText())
            val savedTime = json.getLong("time")
            if (System.currentTimeMillis() - savedTime > CACHE_EXPIRE_MS) {
                file.delete()
                return emptyList()
            }
            val arr = json.getJSONArray("servers")
            val servers = mutableListOf<VpnServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                servers.add(VpnServer(
                    hostname = obj.getString("hostname"),
                    ip = obj.getString("ip"),
                    score = obj.getLong("score"),
                    ping = obj.getInt("ping"),
                    speed = obj.getLong("speed"),
                    ovpnBase64 = obj.getString("ovpn"),
                    countryShort = obj.optString("countryShort", ""),
                    countryLong = obj.optString("countryLong", "")
                ))
            }
            servers
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCache(context: Context, servers: List<VpnServer>) {
        try {
            val arr = JSONArray()
            servers.forEach { s ->
                arr.put(JSONObject().apply {
                    put("hostname", s.hostname)
                    put("ip", s.ip)
                    put("score", s.score)
                    put("ping", s.ping)
                    put("speed", s.speed)
                    put("ovpn", s.ovpnBase64)
                    put("countryShort", s.countryShort)
                    put("countryLong", s.countryLong)
                })
            }
            val json = JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("servers", arr)
            }
            File(context.filesDir, CACHE_FILE).writeText(json.toString())
        } catch (_: Exception) {}
    }

    fun parseServers(csv: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        var headerFound = false
        for (line in csv.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.contains("HostName") && trimmed.contains("OpenVPN_ConfigData_Base64")) {
                headerFound = true
                continue
            }
            if (trimmed.startsWith("*") || trimmed.startsWith("%") || trimmed.startsWith("#")) continue
            if (!headerFound) continue
            val cols = trimmed.split(",")
            if (cols.size < 15) continue
            try {
                val countryShort = cols[6].trim()
                if (countryShort in EXCLUDED_COUNTRIES) continue
                val ovpnBase64 = cols[14].trim()
                if (ovpnBase64.isBlank()) continue
                servers.add(VpnServer(
                    hostname = cols[0].trim(),
                    ip = cols[1].trim(),
                    score = cols[2].trim().toLongOrNull() ?: 0L,
                    ping = cols[3].trim().toIntOrNull() ?: 9999,
                    speed = cols[4].trim().toLongOrNull() ?: 0L,
                    ovpnBase64 = ovpnBase64,
                    countryShort = countryShort,
                    countryLong = cols[5].trim()
                ))
            } catch (_: Exception) { continue }
        }
        return servers
    }

    fun saveOvpnFile(context: Context, server: VpnServer): File? {
        return try {
            if (server.ovpnBase64.isBlank()) return null
            val ovpnBytes = Base64.decode(server.ovpnBase64, Base64.DEFAULT)
            // IP 기반 파일명 → 같은 서버면 덮어쓰기, OpenVPN 재저장 불필요
            val file = File(context.cacheDir, "vpngate_${server.ip.replace(".", "_")}.ovpn")
            file.writeBytes(ovpnBytes)
            file
        } catch (_: Exception) { null }
    }

    fun formatSpeed(bps: Long): String {
        return when {
            bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
            bps >= 1_000 -> "%.1f Kbps".format(bps / 1_000.0)
            else -> "${bps} bps"
        }
    }
}
