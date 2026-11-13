package com.waizusora.wdb.adb

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Validates numeric network endpoints before opening an ADB transport.
 *
 * Hostnames are intentionally rejected: Android Wireless Debugging displays a
 * concrete IP address and numeric endpoints avoid ambiguous DNS resolution.
 */
internal object AdbEndpointValidator {
    fun validate(host: String, port: Int) {
        require(host.isNotBlank() && host.length <= 253) {
            "A valid device IP address is required."
        }
        require(port in 1..65535) {
            "Port must be between 1 and 65535."
        }

        val ipv4 = host.split(".").let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        val ipv6 = host.contains(':') && host.matches(Regex("[0-9a-fA-F:.%]+")) &&
            runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        require(ipv4 || ipv6) {
            "Enter a numeric IPv4 or IPv6 address."
        }
    }
}
