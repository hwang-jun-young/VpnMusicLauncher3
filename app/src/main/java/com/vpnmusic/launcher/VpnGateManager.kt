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
    private const val CACHE_EXPIRE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PING_TIMEOUT_MS = 2000L
    private const val API_TIMEOUT_MS = 10000L
    private const val TOP_SERVERS_TO_PING = 5
    private val EXCLUDED_COUNTRIES = setOf("KR")

    data class VpnServer(
        val hostname: String,
        val ip: String,
        val score: Long,
        val ping: Int,
        val speed: Long,
        val ovpnBase64: String,
        var isChecked: Boolean = false,
        val isManual: Boolean = false  // 수동 입력 서버 여부
    )

    // 저장된 서버 목록 불러오기
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
                    isManual = obj.optBoolean("manual", false)
                ))
            }
            servers
        } catch (_: Exception) { mutableListOf() }
    }

    // 저장된 서버 목록 저장
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
                    put("manual", s.isManual)
                })
            }
            File(context.filesDir, SAVED_SERVERS_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    // 체크된 서버 목록
    fun getCheckedServers(context: Context): List<VpnServer> {
        return loadSavedServers(context).filter { it.isChecked }
    }

    // 수동 입력 서버용 .ovpn 파일 자동 생성
    fun generateOvpnForManualServer(ip: String, port: Int = 443): String {
        val ovpnContent = """
client
dev tun
proto tcp
remote $ip $port
resolv-retry infinite
nobind
persist-key
persist-tun
cipher AES-128-CBC
auth SHA1
verb 3

<ca>
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoBggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa7hFOxEwGn+e
EibG5RMzYMBIlBjKTBhJzKTBhJzKTBhJzKTBhJ==
-----END CERTIFICATE-----
</ca>
        """.trimIndent()

        return Base64.encodeToString(ovpnContent.toByteArray(), Base64.DEFAULT)
    }

    // .ovpn 파일 저장
    fun saveOvpnFile(context: Context, server: VpnServer): File? {
        return try {
            val ovpnBytes = if (server.isManual || server.ovpnBase64.isBlank()) {
                // 수동 입력 서버는 기본 ovpn 템플릿으로 생성
                generateManualOvpn(server.ip).toByteArray()
            } else {
                Base64.decode(server.ovpnBase64, Base64.DEFAULT)
            }
            val file = File(context.cacheDir, "vpngate.ovpn")
            file.writeBytes(ovpnBytes)
            file
        } catch (_: Exception) { null }
    }

    // 수동 서버용 ovpn 텍스트 직접 생성
    private fun generateManualOvpn(ip: String, port: Int = 443): String {
        return """
client
dev tun
proto tcp
remote $ip $port
resolv-retry infinite
nobind
persist-key
persist-tun
cipher AES-128-CBC
auth SHA1
auth-user-pass
verb 3
        """.trimIndent()
    }

    // 새 서버 자동 검색
    suspend fun getBestServer(context: Context): VpnServer? = withContext(Dispatchers.IO) {
        val servers = fetchFastestServers(context)
        if (servers.isEmpty()) return@withContext null

        val topServers = servers.take(TOP_SERVERS_TO_PING)
        val pingResults = coroutineScope {
            topServers.map { server ->
                async { Pair(server, measurePing(server.ip)) }
            }.awaitAll()
        }

        pingResults
            .filter { it.second != -1 }
            .minByOrNull { it.second }
            ?.first
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
                    ovpnBase64 = obj.getString("ovpn")
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
                })
            }
            val json = JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("servers", arr)
            }
            File(context.filesDir, CACHE_FILE).writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun parseServers(csv: String): List<VpnServer> {
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
                    ovpnBase64 = ovpnBase64
                ))
            } catch (_: Exception) { continue }
        }
        return servers
    }

    fun formatSpeed(bps: Long): String {
        return when {
            bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
            bps >= 1_000 -> "%.1f Kbps".format(bps / 1_000.0)
            else -> "${bps} bps"
        }
    }
}
