package com.industrialble.tools

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Herramientas extra: DNS, Base64, MAC lookup, Ping, etc.
 */
object ExtraTools {

    // ===== DNS LOOKUP =====
    data class DnsResult(
        val hostname: String = "",
        val ips: List<String> = emptyList(),
        val canonicalName: String = ""
    )

    fun dnsLookup(hostname: String): DnsResult {
        return try {
            val addr = InetAddress.getByName(hostname)
            val allByName = InetAddress.getAllByName(hostname)
            DnsResult(
                hostname = hostname,
                ips = allByName.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() },
                canonicalName = addr.canonicalHostName ?: hostname
            )
        } catch (e: Exception) {
            DnsResult(hostname = hostname)
        }
    }

    // ===== REVERSE DNS =====
    fun reverseDns(ip: String): String {
        return try {
            InetAddress.getByName(ip).hostName ?: ip
        } catch (_: Exception) { ip }
    }

    // ===== BASE64 =====
    fun base64Encode(input: String): String {
        return try {
            android.util.Base64.encodeToString(input.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    fun base64Decode(input: String): String {
        return try {
            String(android.util.Base64.decode(input, android.util.Base64.DEFAULT))
        } catch (_: Exception) { "" }
    }

    // ===== SUBNET CALCULATOR =====
    data class SubnetInfo(
        val networkAddress: String = "",
        val broadcastAddress: String = "",
        val firstHost: String = "",
        val lastHost: String = "",
        val totalHosts: Int = 0,
        val netmask: String = "",
        val cidr: Int = 0,
        val isPrivate: Boolean = false
    )

    fun calculateSubnet(ipWithCidr: String): SubnetInfo {
        return try {
            val parts = ipWithCidr.split("/")
            val ip = parts[0]
            val cidr = parts.getOrElse(1) { "24" }.toInt()

            val ipBytes = ip.split(".").map { it.toInt() and 0xFF }
            val mask = (0xFFFFFFFF.toInt() shl (32 - cidr))
            val network = ipBytes.mapIndexed { i, _ -> ipBytes[i] and (mask shr (24 - 8 * i) and 0xFF) }
            val broadcast = network.mapIndexed { i, _ -> network[i] or (mask.inv() shr (24 - 8 * i) and 0xFF) }
            val firstHost = network.toMutableList().apply { set(3, get(3) + 1) }
            val lastHost = broadcast.toMutableList().apply { set(3, get(3) - 1) }
            val totalHosts = (1 shl (32 - cidr)) - 2

            val networkStr = network.joinToString(".")
            val broadcastStr = broadcast.joinToString(".")
            val firstHostStr = firstHost.joinToString(".")
            val lastHostStr = lastHost.joinToString(".")
            val maskStr = (0..3).joinToString(".") { (mask shr (24 - 8 * it) and 0xFF).toString() }

            val privateRanges = listOf(
                Pair("10.0.0.0", "10.255.255.255"),
                Pair("172.16.0.0", "172.31.255.255"),
                Pair("192.168.0.0", "192.168.255.255")
            )

            val ipInt = ipBytes.fold(0) { acc, b -> (acc shl 8) or b }
            val isPrivate = privateRanges.any { (start, end) ->
                val s = start.split(".").fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val e = end.split(".").fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                ipInt in s..e
            }

            SubnetInfo(
                networkAddress = networkStr,
                broadcastAddress = broadcastStr,
                firstHost = firstHostStr,
                lastHost = lastHostStr,
                totalHosts = if (totalHosts < 0) 0 else totalHosts,
                netmask = maskStr,
                cidr = cidr,
                isPrivate = isPrivate
            )
        } catch (_: Exception) { SubnetInfo() }
    }

    // ===== MAC VENDOR LOOKUP (local OUI) =====
    fun lookupMacVendor(mac: String): String {
        val cleanMac = mac.replace(":", "").replace("-", "").replace(".", "").take(6).uppercase()
        return OUI_MAP[cleanMac] ?: "Desconocido"
    }

    /** Valida formato MAC */
    fun validateMac(mac: String): Boolean {
        val clean = mac.replace(":", "").replace("-", "").replace(".", "")
        return clean.length == 12 && clean.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
    }

    /** Valida formato IP */
    fun validateIp(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        } catch (_: Exception) { false }
    }

    // ===== HASH GENERATION =====
    data class HashResult(val md5: String = "", val sha1: String = "", val sha256: String = "")

    fun generateHashes(input: String): HashResult {
        return try {
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")

            HashResult(
                md5 = md5.digest(input.toByteArray()).joinToString("") { "%02x".format(it) },
                sha1 = sha1.digest(input.toByteArray()).joinToString("") { "%02x".format(it) },
                sha256 = sha256.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
            )
        } catch (_: Exception) { HashResult() }
    }

    // ===== PORT KNOCKING =====
    data class KnockResult(val port: Int, val success: Boolean)

    fun portKnock(ip: String, ports: List<Int>, timeoutMs: Int = 200): List<KnockResult> {
        return ports.map { port ->
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
                socket.close()
                KnockResult(port, true)
            } catch (_: Exception) {
                KnockResult(port, false)
            }
        }
    }

    // ===== IP LOOKUP (my IP) =====
    fun getPublicIp(): String {
        return try {
            val url = URL("https://api.ipify.org")
            url.readText().trim()
        } catch (_: Exception) { "No disponible" }
    }

    /** Obtiene IP local */
    fun getLocalIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.name.contains("wlan") || intf.name.contains("eth")) {
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            }
            "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }

    // OUI map básico
    private val OUI_MAP = mapOf(
        "00037F" to "Cisco",
        "0015E1" to "ASUSTek",
        "001D0F" to "Intel",
        "0021D8" to "Belkin",
        "002387" to "Samsung",
        "0025D3" to "Apple",
        "0026BB" to "Apple",
        "0026C0" to "Xiaomi",
        "002CD0" to "Xiaomi",
        "0030D1" to "Google",
        "0036FC" to "Samsung",
        "00401F" to "Arcadyan",
        "0050F2" to "Microchip",
        "0057D2" to "ZTE",
        "006082" to "Zyxel",
        "006CF5" to "Huawei",
        "00904C" to "TP-Link",
        "00B0D0" to "Linksys",
        "00C0CA" to "TP-Link",
        "00D06E" to "Motorola",
        "00E04C" to "Realtek",
        "00E06E" to "Cisco",
        "0C1539" to "Intel",
        "0C6E4F" to "Amazon",
        "0C8268" to "TP-Link",
        "0CB862" to "Intel",
        "0CD676" to "D-Link",
        "140708" to "Samsung",
        "14B31F" to "TP-Link",
        "14CFE2" to "TP-Link",
        "14EB02" to "Google",
        "14F65A" to "ASUSTek",
        "183451" to "Raspberry Pi",
        "18A6F7" to "Raspberry Pi",
        "1CC1DE" to "ASUS",
        "1CFA68" to "TP-Link",
        "241FDA" to "ASUS",
        "24F094" to "Apple",
        "283695" to "TP-Link",
        "2C198A" to "Xiaomi",
        "2CF0EE" to "Ubiquiti",
        "34A395" to "Intel",
        "34C06F" to "D-Link",
        "3C5A37" to "Intel",
        "3C8AB0" to "Xiaomi",
        "3CD16E" to "ASUSTek",
        "40D32D" to "TP-Link",
        "48A2E6" to "TP-Link",
        "4C28B7" to "D-Link",
        "525400" to "VirtualBox/QEMU",
        "54E6FC" to "ASUSTek",
        "5C93A2" to "D-Link",
        "64E682" to "D-Link",
        "74DA38" to "Apple",
        "7825AD" to "TP-Link",
        "7CC537" to "Dell",
        "801F02" to "Intel",
        "80C5F2" to "Huawei",
        "843A4B" to "TP-Link",
        "84A8E4" to "TP-Link",
        "8C705A" to "Intel",
        "8CC8CD" to "Zyxel",
        "94CCB9" to "TP-Link",
        "9C28BF" to "Xiaomi",
        "A44E31" to "TP-Link",
        "A4C0E1" to "ASUSTek",
        "AC162D" to "TP-Link",
        "B0BE76" to "TP-Link",
        "B407F9" to "TP-Link",
        "B41489" to "Netgear",
        "B82ADC" to "ASUSTek",
        "B83861" to "TP-Link",
        "B8C75D" to "Apple",
        "BC3F8F" to "ASUSTek",
        "BCB151" to "Samsung",
        "C02973" to "D-Link",
        "C03F0E" to "TP-Link",
        "C82A14" to "Intel",
        "C86C87" to "TP-Link",
        "C888A6" to "Samsung",
        "C8E7D8" to "ASUSTek",
        "CC3E5F" to "TP-Link",
        "CC40D0" to "ASUSTek",
        "CC6DA0" to "TP-Link",
        "CC9E00" to "Netgear",
        "CCB55A" to "TP-Link",
        "D02EE1" to "D-Link",
        "D035D2" to "ASUSTek",
        "D0421E" to "TP-Link",
        "E030F0" to "TP-Link",
        "E0615E" to "Samsung",
        "E064BB" to "Apple",
        "E0655B" to "ASUSTek",
        "E06E27" to "Samsung",
        "E0ACB1" to "LG",
        "E0B9A5" to "ASUSTek",
        "E0C286" to "Amazon",
        "E0D1E6" to "Huawei",
        "E0F5C6" to "Apple",
        "E0F847" to "Intel",
        "E422A5" to "TP-Link",
        "E424C6" to "Huawei",
        "E435FB" to "OnePlus",
        "E440E2" to "TP-Link",
        "E454E8" to "TP-Link",
        "E45AA2" to "Apple",
        "E46549" to "D-Link",
        "E47CF9" to "Intel",
        "E498D6" to "TP-Link",
        "E4A316" to "ZTE",
        "E4B97A" to "TP-Link",
        "E4F17C" to "Xiaomi",
        "F8A45F" to "Xiaomi",
        "FCB991" to "Xiaomi",
        "FCBD67" to "TP-Link",
        "FCEDB9" to "Apple",
        "FCFC48" to "TP-Link"
    )
}
