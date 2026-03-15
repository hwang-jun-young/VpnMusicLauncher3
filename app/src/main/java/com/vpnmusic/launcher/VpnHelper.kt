package com.vpnmusic.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.FileProvider

object VpnHelper {

    const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
    const val MORF_PACKAGE = "app.morphe.android.apps.youtube.music"
    const val VPN_CHECK_INTERVAL_MS = 2000L
    const val VPN_TIMEOUT_MS = 60000L

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    fun launchOpenVpnWithProfile(context: Context, ovpnPath: String): Boolean {
        return try {
            val file = java.io.File(ovpnPath)
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-openvpn-profile")
                setPackage(OPENVPN_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    fun launchMorf(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(MORF_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) { false }
    }
}
