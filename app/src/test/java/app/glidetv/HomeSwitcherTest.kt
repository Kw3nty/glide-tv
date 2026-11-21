package app.glidetv

import app.glidetv.home.HomeClaimant
import app.glidetv.home.HomeDevice
import app.glidetv.home.HomeStore
import app.glidetv.home.HomeSwitcher
import app.glidetv.home.RestoreResult
import app.glidetv.home.SwitchResult
import app.glidetv.home.WifiOwners
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SELF = "app.glidetv"
private const val ALIAS = "$SELF/${HomeSwitcher.ALIAS}"
private const val TVLAUNCHER = "com.google.android.tvlauncher"
private const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
private const val WIZARD = "com.google.android.tungsten.setupwraith"
private const val SETTINGS = "com.android.tv.settings"

/**
 * Телевизор, каким его видит [HomeSwitcher]. Home достаётся претенденту с наибольшим приоритетом —
 * как в Android; при равенстве решает set-home-activity.
 *
 * [violated] — главное, что проверяют тесты: мастер настройки включён при выключенном лаунчере.
 * На настоящем Google TV в этом состоянии Home уходит мастеру и система может зациклиться.
 */
private class FakeTv(private val stock: String, wizard: Boolean) : HomeDevice, HomeStore {
    override val self = SELF
    override var hasShell = true
    override var previousHome = ""
    override var disabledStock = ""

    val enabled = HashMap<String, Boolean>().apply {
        put(stock, true)
        if (wizard) put(WIZARD, true)
    }
    val extra = ArrayList<HomeClaimant>()
    val ops = ArrayList<String>()
    val refuseDisable = HashSet<String>()
    val refuseEnable = HashSet<String>()
    var aliasOn = false
    var preferred: String? = null
    var owners: Set<String>? = setOf("android.uid.system")
    var onWifi = true
    var violated = false

    /** Телевизор, на котором Home после отключения лаунчера достаётся неизвестно кому. */
    var homeStolenBy: String? = null

    private fun on(pkg: String) = enabled[pkg] == true

    private fun check() {
        if (WIZARD in enabled && on(WIZARD) && !on(stock)) violated = true
    }

    override fun claimants(): List<HomeClaimant> = buildList {
        if (on(stock)) add(HomeClaimant(stock, "$stock.HomeActivity", 2))
        if (on(WIZARD)) add(HomeClaimant(WIZARD, "$WIZARD.RecoveryActivity", 1))
        if (aliasOn) add(HomeClaimant(SELF, HomeSwitcher.ALIAS, 0))
        addAll(extra)
        add(HomeClaimant(SETTINGS, "$SETTINGS.system.FallbackHome", -1000))
    }

    override fun resolvedHome(): String? {
        if (!on(stock)) homeStolenBy?.let { return it }
        val all = claimants()
        val top = all.maxOf { it.priority }
        val best = all.filter { it.priority == top }
        if (best.size == 1) return "${best[0].pkg}/${best[0].cls}"
        return best.map { "${it.pkg}/${it.cls}" }.firstOrNull { it == preferred } ?: "android/com.android.internal.app.ResolverActivity"
    }

    override fun isEnabled(pkg: String) = on(pkg)
    override fun isAliasEnabled() = aliasOn
    override fun setAlias(enabled: Boolean) { aliasOn = enabled }
    override fun label(pkg: String) = pkg
    override fun wifiInUse() = onWifi

    override suspend fun disable(pkg: String) {
        ops += "disable $pkg"
        if (pkg !in refuseDisable) enabled[pkg] = false
        check()
    }

    override suspend fun enable(pkg: String) {
        ops += "enable $pkg"
        if (pkg !in refuseEnable) enabled[pkg] = true
        check()
    }

    override suspend fun setHome(component: String): Boolean {
        ops += "home $component"
        preferred = component
        return true
    }

    override suspend fun wifiOwners() = owners
    override suspend fun settle() = Unit
    override fun log(message: String, warning: Boolean) = Unit

    /** Прошивка включила мастер сама (так делают некоторые Google TV при холодном старте). */
    fun firmwareReenablesWizard() { enabled[WIZARD] = true }

    fun disabled(): List<String> = ops.filter { it.startsWith("disable ") }.map { it.removePrefix("disable ") }
    fun enabledOps(): List<String> = ops.filter { it.startsWith("enable ") }.map { it.removePrefix("enable ") }
}

class HomeSwitcherTest {
    private fun androidTv() = FakeTv(TVLAUNCHER, wizard = false)
    private fun googleTv() = FakeTv(LAUNCHERX, wizard = true)
    private fun switcher(tv: FakeTv) = HomeSwitcher(tv, tv)

    // ── Android TV: мастера нет ──

    @Test fun androidTvFullCycle() = runBlocking {
        val tv = androidTv()
        val s = switcher(tv)
        assertEquals(SwitchResult.STOCK_STILL_HOME, s.apply())
        assertEquals("$TVLAUNCHER/$TVLAUNCHER.HomeActivity", tv.previousHome)
        assertTrue(s.status().stockCanBeDisabled)
        assertEquals("", s.status().wizardLabel)

        assertEquals(SwitchResult.DONE, s.disableStock())
        assertEquals(listOf(TVLAUNCHER), tv.disabled())
        assertEquals(ALIAS, tv.resolvedHome())
        assertEquals(TVLAUNCHER, tv.disabledStock)
        assertTrue(s.status().glideIsHome)

        assertEquals(RestoreResult.DONE, s.restore())
        assertTrue(tv.isEnabled(TVLAUNCHER))
        assertFalse(tv.aliasOn)
        assertEquals("", tv.disabledStock)
        assertEquals("", tv.previousHome)
        assertEquals("$TVLAUNCHER/$TVLAUNCHER.HomeActivity", tv.resolvedHome())
    }

    @Test fun withoutTurboNothingChanges() = runBlocking {
        val tv = androidTv().apply { hasShell = false }
        assertEquals(SwitchResult.NEED_TURBO, switcher(tv).apply())
        assertFalse(tv.aliasOn)
        assertTrue(tv.ops.isEmpty())
    }

    @Test fun restoresAValueWrittenByVersion030() = runBlocking {
        val tv = androidTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        // 0.3.0 хранила один пакет без запятых — формат тот же
        assertEquals(TVLAUNCHER, tv.disabledStock)
        assertEquals(RestoreResult.DONE, switcher(tv).restore())
        assertTrue(tv.isEnabled(TVLAUNCHER))
    }

    // ── Google TV: мастер настройки претендует на Home ──

    @Test fun googleTvDisablesWizardBeforeLauncher() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        assertEquals(SwitchResult.STOCK_STILL_HOME, s.apply())
        val status = s.status()
        assertTrue(status.stockCanBeDisabled)
        assertEquals(WIZARD, status.wizardLabel)
        assertEquals("", status.blockedBy)

        assertEquals(SwitchResult.DONE, s.disableStock())
        assertEquals(listOf(WIZARD, LAUNCHERX), tv.disabled())
        assertFalse("мастер был включён при выключенном лаунчере", tv.violated)
        assertEquals(ALIAS, tv.resolvedHome())
        assertEquals("$LAUNCHERX,$WIZARD", tv.disabledStock)
    }

    @Test fun googleTvRestoreEnablesLauncherBeforeWizard() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.ops.clear()

        assertEquals(RestoreResult.DONE, s.restore())
        assertEquals(listOf(LAUNCHERX, WIZARD), tv.enabledOps())
        assertFalse(tv.violated)
        assertTrue(tv.isEnabled(LAUNCHERX) && tv.isEnabled(WIZARD))
        assertFalse(tv.aliasOn)
        assertEquals("", tv.disabledStock)
        assertEquals("$LAUNCHERX/$LAUNCHERX.HomeActivity", tv.resolvedHome())
    }

    @Test fun wizardOwningWifiStopsEverything() = runBlocking {
        val tv = googleTv().apply { owners = setOf("android.uid.system", "$WIZARD:u0a51") }
        val s = switcher(tv)
        s.apply()
        tv.ops.clear()
        assertEquals(SwitchResult.WIFI_OWNED, s.disableStock())
        assertTrue(tv.ops.isEmpty())
        assertEquals("", tv.disabledStock)
        assertTrue(tv.isEnabled(LAUNCHERX) && tv.isEnabled(WIZARD))
    }

    @Test fun unknownWifiOwnerIsARiskOnlyOnWifi() = runBlocking {
        val wireless = googleTv().apply { owners = null; onWifi = true }
        switcher(wireless).apply()
        assertEquals(SwitchResult.WIFI_OWNED, switcher(wireless).disableStock())
        assertTrue(wireless.disabled().isEmpty())

        val cabled = googleTv().apply { owners = emptySet(); onWifi = false }
        switcher(cabled).apply()
        assertEquals(SwitchResult.DONE, switcher(cabled).disableStock())
        assertFalse(cabled.violated)
    }

    @Test fun unknownSystemAppClaimingHomeIsNeverDisabled() = runBlocking {
        val tv = androidTv().apply { extra += HomeClaimant("com.vendor.oobe", "com.vendor.oobe.Main", 1) }
        val s = switcher(tv)
        tv.previousHome = "$TVLAUNCHER/$TVLAUNCHER.HomeActivity"
        s.apply()
        tv.ops.clear()
        assertEquals("com.vendor.oobe", s.status().blockedBy)
        assertFalse(s.status().stockCanBeDisabled)
        assertEquals(SwitchResult.BLOCKED, s.disableStock())
        assertTrue(tv.ops.isEmpty())
        assertEquals("", tv.disabledStock)
    }

    @Test fun wizardThatRefusesToTurnOffLeavesLauncherAlone() = runBlocking {
        val tv = googleTv().apply { refuseDisable += WIZARD }
        val s = switcher(tv)
        s.apply()
        assertEquals(SwitchResult.FAILED, s.disableStock())
        assertEquals(listOf(WIZARD), tv.disabled())
        assertTrue(tv.isEnabled(LAUNCHERX))
        assertFalse(tv.violated)
        assertEquals("", tv.disabledStock)
    }

    @Test fun rollbackEnablesLauncherBeforeWizard() = runBlocking {
        val tv = googleTv().apply { homeStolenBy = "com.vendor.other/.Home" }
        val s = switcher(tv)
        s.apply()
        tv.ops.clear()
        assertEquals(SwitchResult.FAILED, s.disableStock())
        assertEquals(listOf(WIZARD, LAUNCHERX), tv.disabled())
        assertEquals(listOf(LAUNCHERX, WIZARD), tv.enabledOps())
        assertFalse(tv.violated)
        assertTrue(tv.isEnabled(LAUNCHERX) && tv.isEnabled(WIZARD))
        assertEquals("", tv.disabledStock)
    }

    @Test fun rollbackKeepsWizardOffWhenLauncherCannotComeBack() = runBlocking {
        val tv = googleTv().apply { homeStolenBy = "com.vendor.other/.Home"; refuseEnable += LAUNCHERX }
        val s = switcher(tv)
        s.apply()
        assertEquals(SwitchResult.FAILED, s.disableStock())
        assertFalse(tv.isEnabled(WIZARD))
        assertFalse(tv.violated)
        // запись осталась: «Вернуть как было» попробует ещё раз
        assertEquals("$LAUNCHERX,$WIZARD", tv.disabledStock)
    }

    @Test fun restoreWithoutTurboAsksForIt() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.hasShell = false
        tv.ops.clear()
        assertEquals(RestoreResult.NEED_TURBO, s.restore())
        assertTrue(tv.ops.isEmpty())
        assertTrue(tv.aliasOn)
    }

    @Test fun restoreKeepsWizardOffWhenLauncherCannotComeBack() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.refuseEnable += LAUNCHERX
        assertEquals(RestoreResult.FAILED, s.restore())
        assertFalse(tv.isEnabled(WIZARD))
        assertFalse(tv.violated)
        assertTrue("экран Glide TV должен остаться единственным Home", tv.aliasOn)
        assertEquals(ALIAS, tv.resolvedHome())
    }

    // ── После загрузки ──

    @Test fun healTurnsWizardOffAgainAfterFirmwareReenabledIt() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.firmwareReenablesWizard()
        tv.ops.clear()

        assertTrue(s.heal())
        assertEquals(listOf(WIZARD), tv.disabled())
        assertFalse(tv.isEnabled(WIZARD))
        assertFalse(tv.isEnabled(LAUNCHERX))
        assertEquals(ALIAS, tv.resolvedHome())
    }

    @Test fun healGivesHomeBackToLauncherWhenWizardStaysOn() = runBlocking {
        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.firmwareReenablesWizard()
        tv.refuseDisable += WIZARD

        assertTrue(s.heal())
        assertTrue(tv.isEnabled(LAUNCHERX))
        assertEquals("$LAUNCHERX/$LAUNCHERX.HomeActivity", tv.resolvedHome())
        assertEquals("", tv.disabledStock)
    }

    @Test fun healDoesNothingWhenAllIsWell() = runBlocking {
        val fresh = googleTv()
        assertFalse(switcher(fresh).heal())

        val tv = googleTv()
        val s = switcher(tv)
        s.apply()
        s.disableStock()
        tv.ops.clear()
        assertFalse(s.heal())
        assertTrue(tv.ops.isEmpty())
    }

    @Test fun parsesWifiOwners() {
        val dump = "ID: 0 SSID: \"home\" cuid=1000 cname=android.uid.system luid=1000\n" +
            "ID: 1 SSID: \"guest\" cuid=10051 cname=$WIZARD luid=10051"
        assertEquals(setOf("android.uid.system", WIZARD), WifiOwners.parse(dump))
        assertTrue(WifiOwners.parse("no networks").isEmpty())
    }
}
