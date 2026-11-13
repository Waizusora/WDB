package com.waizusora.wdb.adb

import org.junit.Assert.assertThrows
import org.junit.Test

class AdbEndpointValidatorTest {
    @Test
    fun acceptsValidIpv4Endpoint() {
        AdbEndpointValidator.validate("192.168.1.42", 37123)
    }

    @Test
    fun acceptsValidIpv6Endpoint() {
        AdbEndpointValidator.validate("fe80::1234", 5555)
    }

    @Test
    fun rejectsHostnames() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("android-device.local", 5555)
        }
    }

    @Test
    fun rejectsInvalidIpv4Octets() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("192.168.1.999", 5555)
        }
    }

    @Test
    fun rejectsOutOfRangePorts() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("192.168.1.42", 65536)
        }
    }

    @Test
    fun rejectsEmptyHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("", 5555)
        }
    }
    @Test
    fun rejectsMalformedIpv6Addresses() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("::::", 5555)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("12345::1", 5555)
        }
    }

    @Test
    fun rejectsZeroPort() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbEndpointValidator.validate("192.168.1.42", 0)
        }
    }

}
