// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class IconAssetsTest {
    private val icons = Path.of("icons")

    private fun decode(bytes: ByteArray) = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Image does not decode")

    @Test fun `window icon resource decodes with transparent corners`() {
        val bytes = javaClass.classLoader.getResourceAsStream(WINDOW_ICON_RESOURCE)?.use { it.readBytes() } ?: error("Missing icon resource")
        val image = decode(bytes)
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        assertEquals(255, image.getRGB(128, 128) ushr 24)
        assertNotNull(loadWindowIcon())
    }

    @Test fun `installer icons decode with their expected sizes`() {
        val png = decode(Files.readAllBytes(icons.resolve("keyrook.png")))
        assertEquals(512 to 512, png.width to png.height)
        assertTrue(Files.readString(icons.resolve("keyrook.svg")).contains("SPDX-License-Identifier: GPL-3.0-or-later"))

        val ico = ByteBuffer.wrap(Files.readAllBytes(icons.resolve("keyrook.ico"))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, ico.getShort(0).toInt())
        assertEquals(1, ico.getShort(2).toInt())
        val icoSizes = (0 until ico.getShort(4).toInt()).map { index ->
            val entry = 6 + 16 * index
            val stored = ico.get(entry).toInt() and 0xFF
            val length = ico.getInt(entry + 8)
            val offset = ico.getInt(entry + 12)
            val image = decode(ico.array().copyOfRange(offset, offset + length))
            val size = if (stored == 0) 256 else stored
            assertEquals(size to size, image.width to image.height)
            size
        }
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), icoSizes)

        val icns = ByteBuffer.wrap(Files.readAllBytes(icons.resolve("keyrook.icns")))
        assertEquals("icns", String(icns.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(icns.capacity(), icns.getInt(4))
        val icnsSizes = mutableMapOf<String, Int>()
        var position = 8
        while (position < icns.capacity()) {
            val type = String(icns.array(), position, 4, Charsets.US_ASCII)
            val length = icns.getInt(position + 4)
            val image = decode(icns.array().copyOfRange(position + 8, position + length))
            assertEquals(image.width, image.height)
            icnsSizes[type] = image.width
            position += length
        }
        assertEquals(mapOf("ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024), icnsSizes)
    }
}
