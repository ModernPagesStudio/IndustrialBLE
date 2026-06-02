package com.industrialble.network

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Escanea la red local descubriendo dispositivos vía ARP y ping.
 */
class NetworkScanner(private val context: Context) {

    data class NetworkDevice(
        val ip: String,
        val mac: String = "",
        val vendor: String = "",
        val hostname: String = "",
        val isReachable: Boolean = false
    )

    /** Obtiene la IP local y la puerta de enlace */
    fun getNetworkInfo(): Pair<String, String> {
        var localIp = "0.0.0.0"
        var gateway = "0.0.0.0"

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.name.contains("wlan") || intf.name.contains("eth")) {
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            localIp = addr.hostAddress ?: localIp
                        }
                    }
                }
            }

            try {
                val reader = BufferedReader(InputStreamReader(Runtime.getRuntime().exec("cat /proc/net/route").inputStream))
                reader.readLine()
                val line = reader.readLine()
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        val hexGateway = parts[2]
                        gateway = hexToIp(hexGateway)
                    }
                }
                reader.close()
            } catch (_: Exception) {
                gateway = localIp.substringBeforeLast(".") + ".1"
            }
        } catch (_: Exception) {}

        return Pair(localIp, gateway)
    }

    private fun hexToIp(hex: String): String {
        return try {
            val ip = hex.padStart(8, '0')
            "${ip[6]}${ip[7]}.${ip[4]}${ip[5]}.${ip[2]}${ip[3]}.${ip[0]}${ip[1]}"
                .split(".")
                .joinToString(".") { Integer.parseInt(it, 16).toString() }
        } catch (_: Exception) { "0.0.0.0" }
    }

    /** Escanea la LAN haciendo ping a cada IP del rango */
    fun scanNetwork(subnet: String, progressCallback: (Int, Int) -> Unit): List<NetworkDevice> {
        val devices = mutableListOf<NetworkDevice>()
        val prefix = subnet.substringBeforeLast(".")
        val totalHosts = 254

        for (i in 1..254) {
            val ip = "$prefix.$i"
            progressCallback(i, totalHosts)
            try {
                val addr = InetAddress.getByName(ip)
                if (addr.isReachable(300)) {
                    val mac = getMacAddress(ip)
                    val hostname = addr.hostName ?: ip
                    devices.add(
                        NetworkDevice(
                            ip = ip,
                            mac = mac,
                            vendor = lookupVendor(mac),
                            hostname = if (hostname != ip) hostname else "",
                            isReachable = true
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return devices
    }

    private fun getMacAddress(ip: String): String {
        return try {
            val reader = BufferedReader(InputStreamReader(Runtime.getRuntime().exec("cat /proc/net/arp").inputStream))
            reader.lineSequence().forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[0] == ip) {
                    reader.close()
                    return parts[3].uppercase()
                }
            }
            reader.close()
            ""
        } catch (_: Exception) { "" }
    }

    private fun lookupVendor(mac: String): String {
        return com.industrialble.tools.ExtraTools.lookupMacVendor(mac)
    }

    /** Ping a una IP específica */
    fun ping(ip: String, count: Int = 4): List<Long> {
        val rtts = mutableListOf<Long>()
        try {
            for (i in 0 until count) {
                val start = System.currentTimeMillis()
                val addr = InetAddress.getByName(ip)
                if (addr.isReachable(1000)) {
                    rtts.add(System.currentTimeMillis() - start)
                } else {
                    rtts.add(-1)
                }
            }
        } catch (_: Exception) {}
        return rtts
    }
}
