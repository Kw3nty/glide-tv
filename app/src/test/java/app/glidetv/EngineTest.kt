package app.glidetv

import app.glidetv.debloat.CoreGuard
import app.glidetv.engine.AnimScale
import app.glidetv.engine.PackageName
import app.glidetv.engine.ShellCmd
import app.glidetv.engine.parseExitCode
import app.glidetv.engine.readUntilExitMarker
import app.glidetv.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class EngineTest {
    private val self = PackageName("app.glidetv")

    @Test fun packageNameRejectsShellInjection() {
        assertFalse(PackageName.isValid("com.x; reboot"))
        assertFalse(PackageName.isValid("com.x && rm -rf /"))
        assertFalse(PackageName.isValid("noDots"))
        assertTrue(PackageName.isValid("com.google.android.feedback"))
    }

    @Test fun rendersCommands() {
        assertEquals("pm disable-user --user 0 com.a.b", ShellCmd.DisableUser(PackageName("com.a.b")).render(self))
        assertEquals("pm trim-caches 999G", ShellCmd.TrimCaches.render(self))
        assertTrue(ShellCmd.SetAnimations(AnimScale.HALF).render(self).contains("animator_duration_scale 0.5"))
    }

    @Test fun parsesExitCode() {
        val r = parseExitCode("Success\n__GLIDE_EXIT__0\n")
        assertEquals(0, r.exitCode)
        assertEquals("Success", r.output)
        assertEquals(-1, parseExitCode("no marker").exitCode)
    }

    @Test fun coreGuard() {
        assertTrue(CoreGuard.isNever("com.android.systemui"))
        assertTrue(CoreGuard.isNever("com.android.providers.media"))
        assertFalse(CoreGuard.isNever("com.android.providers.calendar"))
        assertTrue(CoreGuard.isNever("com.google.android.tungsten.setupwraith"))
        assertFalse(CoreGuard.isNever("com.google.android.feedback"))
    }

    @Test fun versions() {
        assertTrue(UpdateChecker.compareVersions("0.2.0", "0.1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.0") == 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.5") > 0)
    }

    /** Как поток libadb 3.1.1 при гонке: данные отдал, а EOF так и не пришёл. */
    private class NoEofStream(data: String) : InputStream() {
        private val src = ByteArrayInputStream(data.toByteArray())
        override fun read(): Int = src.read().also { if (it < 0) Thread.sleep(Long.MAX_VALUE) }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n < 0) Thread.sleep(Long.MAX_VALUE)
            return n
        }
    }

    @Test(timeout = 5_000) fun readsUntilExitMarkerWithoutEof() {
        val out = ByteArrayOutputStream()
        assertTrue(readUntilExitMarker(NoEofStream("Success\n__GLIDE_EXIT__0\n"), out))
        assertEquals(0, parseExitCode(out.toString("UTF-8")).exitCode)
    }

    @Test fun exitMarkerNeedsFullLine() {
        // «__GLIDE_EXIT__1» без перевода строки может быть началом «__GLIDE_EXIT__127»
        val out = ByteArrayOutputStream()
        assertFalse(readUntilExitMarker(ByteArrayInputStream("x\n__GLIDE_EXIT__1".toByteArray()), out))
        assertTrue(readUntilExitMarker(ByteArrayInputStream("Привет\r\n__GLIDE_EXIT__127\r\n".toByteArray()), ByteArrayOutputStream()))
    }
}
