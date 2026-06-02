package com.industrialble.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Escáner de puertos TCP con detección de servicios comunes.
 */
class PortScanner {

    data class ScanResult(
        val port: Int,
        val isOpen: Boolean,
        val serviceName: String = ""
    )

    companion object {
        val COMMON_PORTS = mapOf(
            21 to "FTP",
            22 to "SSH",
            23 to "Telnet",
            25 to "SMTP",
            53 to "DNS",
            80 to "HTTP",
            110 to "POP3",
            111 to "RPC",
            135 to "RPC",
            139 to "NetBIOS",
            143 to "IMAP",
            443 to "HTTPS",
            445 to "SMB",
            465 to "SMTPS",
            500 to "IPsec",
            514 to "Syslog",
            587 to "SMTP",
            593 to "RPC",
            636 to "LDAPS",
            993 to "IMAPS",
            995 to "POP3S",
            1025 to "NFS",
            1080 to "SOCKS",
            1194 to "OpenVPN",
            1352 to "Lotus",
            1433 to "MSSQL",
            1434 to "MSSQL",
            1521 to "Oracle",
            1701 to "L2TP",
            1723 to "PPTP",
            1812 to "RADIUS",
            1883 to "MQTT",
            2049 to "NFS",
            2082 to "cPanel",
            2083 to "cPanel SSL",
            2096 to "cPanel",
            2181 to "ZooKeeper",
            2222 to "SSH Alt",
            2375 to "Docker",
            2376 to "Docker TLS",
            2443 to "HTTPS Alt",
            2483 to "Oracle",
            2484 to "Oracle SSL",
            27017 to "MongoDB",
            28017 to "MongoDB HTTP",
            3000 to "Node/Dev",
            3128 to "Squid",
            3306 to "MySQL",
            3389 to "RDP",
            3690 to "SVN",
            4000 to "IRC",
            4040 to "WebSphere",
            4190 to "Sieve",
            4333 to "mSQL",
            4444 to "Metasploit",
            4500 to "IPsec NAT",
            4848 to "GlassFish",
            4899 to "RAdmin",
            5000 to "HTTP Alt",
            5001 to "HTTP Alt",
            5002 to "HTTP Alt",
            5003 to "FileMaker",
            5038 to "Asterisk",
            5060 to "SIP",
            5061 to "SIP TLS",
            5144 to "Syslog",
            5222 to "XMPP",
            5223 to "XMPP SSL",
            5269 to "XMPP Server",
            5432 to "PostgreSQL",
            5555 to "Android ADB",
            5631 to "PCAnywhere",
            5800 to "VNC HTTP",
            5900 to "VNC",
            5901 to "VNC :1",
            5984 to "CouchDB",
            6000 to "X11",
            6001 to "X11 :1",
            6379 to "Redis",
            6443 to "HTTPS Alt",
            6667 to "IRC",
            6881 to "BitTorrent",
            6969 to "BitTorrent Tracker",
            7001 to "WebLogic",
            7002 to "WebLogic SSL",
            7077 to "Spark",
            8000 to "HTTP Alt",
            8001 to "HTTP Alt",
            8008 to "HTTP Alt",
            8010 to "HTTP Alt",
            8020 to "HTTP Alt",
            8030 to "HTTP Alt",
            8080 to "HTTP Proxy",
            8081 to "HTTP Alt",
            8082 to "HTTP Alt",
            8083 to "HTTP Alt",
            8084 to "HTTP Alt",
            8085 to "HTTP Alt",
            8086 to "InfluxDB",
            8087 to "HTTP Alt",
            8088 to "HTTP Alt",
            8089 to "HTTP Alt",
            8090 to "HTTP Alt",
            8091 to "Couchbase",
            8092 to "Couchbase",
            8093 to "Couchbase",
            8094 to "HTTP Alt",
            8095 to "HTTP Alt",
            8096 to "HTTP Alt",
            8097 to "HTTP Alt",
            8098 to "Riak",
            8099 to "HTTP Alt",
            8100 to "HTTP Alt",
            8181 to "HTTP Alt",
            8200 to "VMware",
            8222 to "VMware",
            8291 to "RouterOS",
            8332 to "Bitcoin",
            8333 to "Bitcoin",
            8443 to "HTTPS Alt",
            8500 to "Consul",
            8600 to "Consul DNS",
            8649 to "Ganglia",
            8686 to "JMX",
            8761 to "Eureka",
            8800 to "HTTP Alt",
            8834 to "Nessus",
            8880 to "HTTP Alt",
            8888 to "HTTP Alt",
            8889 to "HTTP Alt",
            8890 to "HTTP Alt",
            8891 to "HTTP Alt",
            8892 to "HTTP Alt",
            8893 to "HTTP Alt",
            8894 to "HTTP Alt",
            8895 to "HTTP Alt",
            8896 to "HTTP Alt",
            8897 to "HTTP Alt",
            8898 to "HTTP Alt",
            8899 to "HTTP Alt",
            9000 to "HTTP Alt",
            9001 to "HTTP Alt",
            9002 to "HTTP Alt",
            9003 to "HTTP Alt",
            9004 to "HTTP Alt",
            9005 to "HTTP Alt",
            9006 to "HTTP Alt",
            9007 to "HTTP Alt",
            9008 to "HTTP Alt",
            9009 to "HTTP Alt",
            9010 to "HTTP Alt",
            9042 to "Cassandra",
            9043 to "WebSphere SSL",
            9050 to "TOR",
            9051 to "TOR Control",
            9090 to "HTTP Alt",
            9092 to "Kafka",
            9099 to "HTTP Alt",
            9100 to "Printer",
            9200 to "Elasticsearch",
            9300 to "Elasticsearch",
            9418 to "Git",
            9443 to "HTTPS Alt",
            9600 to "Logstash",
            9999 to "HTTP Alt",
            10000 to "Webmin",
            10001 to "HTTP Alt",
            10002 to "HTTP Alt",
            10003 to "HTTP Alt",
            10004 to "HTTP Alt",
            10005 to "HTTP Alt",
            10009 to "DameWare",
            11211 to "Memcached",
            11214 to "Memcached",
            11215 to "Memcached",
            12000 to "Cube",
            12345 to "NetBus",
            13720 to "NetBackup",
            13721 to "NetBackup",
            13722 to "NetBackup",
            13724 to "NetBackup",
            13782 to "NetBackup",
            13783 to "NetBackup",
            20000 to "DNP3",
            22222 to "HTTP Alt",
            23023 to "Telnet",
            23456 to "Owning.com",
            25565 to "Minecraft",
            26000 to "Xoops",
            27015 to "Source Engine",
            27016 to "Source Engine",
            27017 to "MongoDB",
            27374 to "Sub7",
            28015 to "Rust",
            28016 to "Rust RCON",
            30718 to "Lantronix",
            31337 to "Back Orifice",
            32764 to "Router Backdoor",
            33434 to "traceroute",
            37777 to "IPTV",
            37778 to "IPTV",
            37947 to "Murmur",
            41170 to "Motorola",
            41523 to "Shodan",
            44818 to "EtherNet/IP",
            47808 to "BACnet",
            49152 to "Windows RPC",
            49153 to "Windows RPC",
            49154 to "Windows RPC",
            49155 to "Windows RPC",
            49156 to "Windows RPC",
            49157 to "Windows RPC",
            50000 to "SAP",
            50001 to "SAP",
            50002 to "SAP",
            50003 to "SAP",
            50070 to "Hadoop",
            50075 to "Hadoop",
            50100 to "SAP",
            50200 to "SAP",
            50300 to "SAP",
            50400 to "SAP",
            51115 to "Shodan",
            53413 to "Router OS",
            54321 to "PC Anywhere",
            55555 to "Unknown",
            61616 to "ActiveMQ",
            62078 to "iPhone Sync",
            64738 to "Mumble",
            65000 to "Unknown",
            65301 to "PC Anywhere",
            65535 to "Unknown"
        )

        // Los 20 puertos más comunes para scan rápido
        val TOP_20 = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 993, 995, 1433, 1521, 2049, 3306, 3389, 5432, 8080, 8443, 5900, 6379, 27017, 9200)

        // Puertos comunes en Android para escaneo local
        val TOP_10_ANDROID = listOf(80, 443, 8080, 22, 23, 8080, 8443, 3000, 5000, 9090)
    }

    /** Escanea una lista de puertos en una IP */
    suspend fun scanPorts(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int = 500,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val total = ports.size

        for ((index, port) in ports.withIndex()) {
            onProgress(index + 1, total)
            val result = try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.close()
                ScanResult(port, true, COMMON_PORTS[port] ?: "")
            } catch (_: SocketTimeoutException) {
                ScanResult(port, false)
            } catch (_: IOException) {
                ScanResult(port, false)
            } catch (_: Exception) {
                ScanResult(port, false)
            }
            results.add(result)
        }

        return results.sortedBy { it.port }
    }

    /** Escaneo rápido (solo puertos conocidos) */
    suspend fun quickScan(ip: String): List<ScanResult> {
        return scanPorts(ip, TOP_20, 300)
    }
}
