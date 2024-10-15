import io.mockk.every
import io.mockk.mockkStatic
import maestro.utils.SocketUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

class SocketUtilsTest {

    private fun <T> List<T>.toEnumeration(): Enumeration<T> = Collections.enumeration(this)

    @Test
    fun `nextFreePort should return a free port within the specified range`() {
        val from = 5000
        val to = 5100
        val port = SocketUtils.nextFreePort(from, to)

        assertTrue(port in from..to)
    }

    @Test
    fun `nextFreePort should throw IllegalStateException when no ports are available in the range`() {
        val from = 100000
        val to = 100010

        assertThrows(IllegalStateException::class.java) {
            SocketUtils.nextFreePort(from, to)
        }
    }

    @Test
    fun `localIp should return local IP address`() {
        val ip = SocketUtils.localIp()

        assertNotNull(ip)
        assertTrue(ip.startsWith("192") || ip.startsWith("10") || ip.startsWith("172") || ip.startsWith("127"))
        assertTrue(InetAddress.getByName(ip) is Inet4Address)
    }

    @Test
    fun `localIp should return localhost address if no network interfaces are available`() {
        mockkStatic(NetworkInterface::class)
        every { NetworkInterface.getNetworkInterfaces() } returns listOf<NetworkInterface>().toEnumeration()

        val ip = SocketUtils.localIp()

        assertEquals(InetAddress.getLocalHost().hostAddress, ip)
    }
}