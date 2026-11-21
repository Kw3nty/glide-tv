package app.glidetv

import app.glidetv.engine.ComponentRef
import app.glidetv.engine.PackageName
import app.glidetv.engine.ShellCmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeSwitchTest {
    private val self = PackageName("app.glidetv")

    @Test fun setHomeActivityQuotesComponent() {
        val cmd = ShellCmd.SetHomeActivity(ComponentRef(self, "app.glidetv.home.HomeAlias"))
        assertEquals(
            "cmd package set-home-activity --user 0 'app.glidetv/app.glidetv.home.HomeAlias'",
            cmd.render(self),
        )
    }

    @Test fun parsesFullAndShortClassNames() {
        assertEquals(
            "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
            ComponentRef.parse("com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity")?.flatten(),
        )
        // «пакет/.Класс» — так компонент печатает сам Android
        assertEquals(
            "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
            ComponentRef.parse("com.google.android.tvlauncher/.MainActivity")?.flatten(),
        )
        // Внутренний класс: «$» остаётся как есть, в команде он внутри одинарных кавычек
        assertEquals("a.b/a.b.Outer\$Inner", ComponentRef.parse("a.b/a.b.Outer\$Inner")?.flatten())
    }

    @Test fun rejectsAnythingThatCouldBreakOutOfTheCommand() {
        assertNull(ComponentRef.parse(""))
        assertNull(ComponentRef.parse("no.slash.here"))
        assertNull(ComponentRef.parse("a.b/c d"))
        assertNull(ComponentRef.parse("a.b/C'; reboot; '"))
        assertNull(ComponentRef.parse("a.b; reboot/a.b.C"))
        assertNull(ComponentRef.parse("a.b/a.b.C\$(reboot)"))
        assertThrows(IllegalArgumentException::class.java) { ComponentRef(self, "x'; reboot") }
    }
}
