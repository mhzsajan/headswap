package com.yegorkapomidorka.headswap.autosrv

/** Что автоматизация умеет делать с экраном; реализует AccessibilityService, в тестах подменяется. */
interface ServerUi {
    /** Экран готов к работе (включён, без блокировки). false = заблокирован, причина в detail. */
    fun prepareScreen(): Pair<Boolean, String>

    /** Открыть настройки Android Auto заново. false = система не дала запустить. */
    fun openAaSettings(): Boolean

    /** Активное окно принадлежит Android Auto. */
    fun aaVisible(): Boolean

    /** Найти кнопку «Ещё» (contentDescription) и нажать её. */
    fun clickMore(): Boolean

    /** Тексты всех узлов окон AA (меню видно в них же). */
    fun texts(): List<String>

    /** View id (entry name без пакета, как в дампе) узлов окон AA. */
    fun viewIds(): List<String>

    /** Нажать узел (или ближайшего кликабельного предка) с текстом, подходящим под предикат. */
    fun clickText(match: (CharSequence?) -> Boolean): Boolean

    fun back()

    /** Вернуться в наше приложение (или Home). */
    fun returnToApp()

    /** Компактный дамп дерева узлов для лога, когда что-то не нашлось. */
    fun dump(): String

    fun now(): Long

    fun sleep(ms: Long)
}

/**
 * Сценарий «перезапустить сервер головного устройства» в настройках Android Auto:
 * открыть настройки -> ⋮ -> «Остановить…» (если есть) -> ⋮ -> «Запустить…» -> проверка -> назад в приложение.
 * Каждый шаг пишет строку в лог (префикс AUTOSRV ставит вызывающий).
 */
class ServerAutomation(
    private val ui: ServerUi,
    private val log: (String) -> Unit,
    /** Сервер запущен (Запустить нажат и прошла пауза на старт): коннектор можно отпускать. */
    private val onStarted: () -> Unit = {},
    private val pageTimeoutMs: Long = 6_000,
    private val menuTimeoutMs: Long = 3_000,
    private val pollMs: Long = 150,
    private val settleAfterStartMs: Long = 1_000,
) {
    private val t0 = ui.now()

    private fun done(r: AutoResult, detail: String): AutoOutcome {
        val o = AutoOutcome(r, detail, ui.now() - t0)
        log("result: $o")
        return o
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = ui.now() + timeoutMs
        while (true) {
            if (cond()) return true
            if (ui.now() >= end) return false
            ui.sleep(pollMs)
        }
    }

    /** Поверх настроек экран первой настройки AA (согласие/разрешения): дальше нельзя ничего нажимать. */
    private class ConsentScreen : RuntimeException()

    /** Проверка перед каждым действием; при экране согласия прерывает сценарий (restart() его ловит). */
    private fun guard() {
        if (AutoServerText.isConsent(ui.viewIds(), ui.texts())) throw ConsentScreen()
    }

    private fun guardedClick(match: (CharSequence?) -> Boolean): Boolean {
        guard()
        return ui.clickText(match)
    }

    private fun guardedBack() {
        guard()
        ui.back()
    }

    private fun menuHas(): Pair<Boolean, Boolean> {
        val t = ui.texts()
        return Pair(t.any { AutoServerText.isStop(it) }, t.any { AutoServerText.isStart(it) })
    }

    /** Нажать «Ещё» и дождаться пунктов сервера. null = the menu did not open (или без нужных пунктов). */
    private fun openMenu(step: String): Pair<Boolean, Boolean>? {
        for (attempt in 1..2) {
            if (!waitUntil(menuTimeoutMs) { guard(); ui.clickMore() }) {
                log("$step: the «More» button was not found (attempt $attempt)")
                log("dump: " + ui.dump())
                continue
            }
            log("$step: tapped «More» (attempt $attempt)")
            var found: Pair<Boolean, Boolean>? = null
            waitUntil(menuTimeoutMs) {
                guard()
                val h = menuHas()
                if (h.first || h.second) found = h
                found != null
            }
            val f = found
            if (f != null) {
                log("$step: menu open: Stop=${f.first} Start=${f.second}")
                return f
            }
            log("$step: no server items in the menu (attempt $attempt); texts: ${ui.texts().take(20)}")
            log("dump: " + ui.dump())
            guardedBack()
            ui.sleep(400)
        }
        return null
    }

    /** Снова открыть настройки AA и дождаться окна (после «Остановить» оно может закрыться само). */
    private fun reopenAa(): Boolean {
        if (!ui.openAaSettings()) return false
        if (!waitUntil(pageTimeoutMs) { ui.aaVisible() }) return false
        log("the AA window is on screen again")
        guard()
        ui.sleep(300)
        return true
    }

    fun restart(): AutoOutcome = try {
        runScenario()
    } catch (_: ConsentScreen) {
        // без back и returnToApp: диалог должен остаться на экране, пока пользователь не пройдёт настройку
        log("dump: " + ui.dump())
        done(AutoResult.CONSENT, "the phone shows the Android Auto setup screen (consent/permissions), waiting for the user")
    }

    private fun runScenario(): AutoOutcome {
        val (ready, why) = ui.prepareScreen()
        log("screen: $why")
        if (!ready) return done(AutoResult.LOCKED, why)

        if (!ui.openAaSettings()) return done(AutoResult.ERROR, "the system did not allow opening AA settings")
        log("AA settings started, waiting for the window")
        if (!waitUntil(pageTimeoutMs) { ui.aaVisible() }) {
            val (stillReady, why2) = ui.prepareScreen()
            return if (!stillReady) done(AutoResult.LOCKED, why2)
            else done(AutoResult.NOT_FOUND, "the Android Auto window did not appear within ${pageTimeoutMs} ms (background start blocked?)")
        }
        log("the AA window is on screen")
        guard()
        ui.sleep(300)                     // тулбар дорисовывается после появления окна

        var items = openMenu("step 1") ?: return done(AutoResult.NOT_FOUND, "the head unit server menu did not open")
        if (items.first) {
            if (!guardedClick { AutoServerText.isStop(it) }) return done(AutoResult.ERROR, "the «Stop…» item did not respond")
            log("step 2: tapped «Stop head unit server»")
            ui.sleep(700)
            var started = false
            var reopened = false
            for (attempt in 1..5) {
                var next = openMenu("step 3.$attempt")
                if (next == null && !reopened) {
                    // OnePlus/AA 2026-10: после «Остановить» окно настроек AA закрывается (дамп пустой), «Ещё» искать негде
                    reopened = true
                    log("step 3.$attempt: no AA window after «Stop» (visible=${ui.aaVisible()}), reopening settings")
                    if (!reopenAa()) return done(AutoResult.NOT_FOUND, "after «Stop» the AA window disappeared and did not reopen")
                    next = openMenu("step 3.$attempt (reopen)")
                }
                items = next ?: return done(AutoResult.NOT_FOUND, "after «Stop» the menu did not open")
                if (items.second) {
                    if (!guardedClick { AutoServerText.isStart(it) }) return done(AutoResult.ERROR, "the «Start…» item did not respond")
                    log("step 4: tapped «Start head unit server»")
                    started = true
                    break
                }
                log("step 3.$attempt: «Start» is not there yet (the server is stopping), closing the menu and waiting")
                guardedBack()
                ui.sleep(800)
            }
            if (!started) return done(AutoResult.TIMEOUT, "after «Stop» the «Start» item did not appear")
        } else if (items.second) {
            if (!guardedClick { AutoServerText.isStart(it) }) return done(AutoResult.ERROR, "the «Start…» item did not respond")
            log("step 2: the server was stopped, tapped «Start head unit server»")
        } else {
            guardedBack()
            return done(AutoResult.NOT_FOUND, "the menu has neither «Stop» nor «Start»")
        }

        ui.sleep(settleAfterStartMs)
        onStarted()

        // проверка: после «Запустить» в меню должен быть «Остановить…»
        var verified = false
        var startAgain = false
        for (attempt in 1..2) {
            val v = openMenu("verify.$attempt")
            if (v == null) {
                log("verify.$attempt: the menu did not open")
                break
            }
            if (v.first) {
                verified = true
                startAgain = false
                log("verify.$attempt: the menu shows «Stop…» (the server is running)")
                guardedBack()
                ui.sleep(300)
                break
            }
            startAgain = true
            log("verify.$attempt: the menu shows «Start…» again: the server did not start, tapping once more")
            if (!guardedClick { AutoServerText.isStart(it) }) break
            ui.sleep(settleAfterStartMs)
        }
        guard()
        ui.returnToApp()
        return when {
            verified -> done(AutoResult.OK, "server started, confirmed by the menu")
            startAgain -> done(AutoResult.ERROR, "after «Start» the menu offers «Start» again")
            else -> done(AutoResult.OK, "«Start» tapped, could not confirm via the menu")
        }
    }
}
