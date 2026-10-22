package com.yegorkapomidorka.headswap.autosrv

import com.yegorkapomidorka.headswap.aa.AaBridgeCore

/** Что запустило перезапуск сервера ГУ. */
enum class Trigger(val label: String) {
    /** Кнопка в UI или команда adb: всегда, флаг автозапуска не учитывается. */
    BUTTON("button"),

    /** USB_ACCESSORY_ATTACHED в режиме моста, до первой попытки подключения коннектора. */
    USB_ATTACH("USB attached"),

    /** AA-сессия завершилась, а USB всё ещё подключён. */
    AFTER_SESSION("AA session ended"),

    /** Предыдущая попытка упёрлась в заблокированный телефон: повтор, когда его разблокируют. */
    RETRY("retry after lock"),
}

/** CONSENT: на телефоне экран первой настройки Android Auto (согласие/разрешения), автоматизация ничего не трогала. */
enum class AutoResult { OK, LOCKED, NOT_FOUND, TIMEOUT, SERVICE_OFF, ERROR, CONSENT }

class AutoOutcome(val result: AutoResult, val detail: String, val tookMs: Long) {
    override fun toString() = "$result: $detail (${tookMs} ms)"
}

/** Решения «запускать ли автоматизацию» и «что показать на ГУ». Чистая логика, без Android. */
object AutoServerPolicy {
    /** Причина пропуска, пока на телефоне висит экран первой настройки AA (BridgeService по ней показывает подсказку). */
    const val CONSENT_SKIP = "waiting for the user to finish Android Auto setup on the phone"

    /**
     * null = запускать, иначе причина пропуска. startPending: прошлый USB_ATTACH/AFTER_SESSION пропущен, потому что
     * служба ещё не была подключена (после старта процесса она подключается позже сервиса) - RETRY догоняет.
     */
    fun skipReason(
        trigger: Trigger,
        prefEnabled: Boolean,
        serviceConnected: Boolean,
        usbAlive: Boolean,
        warmAaSession: Boolean,
        lastLocked: Boolean,
        startPending: Boolean = false,
        consentHold: Boolean = false,
    ): String? {
        if (!serviceConnected) return "accessibility service is not connected"
        return when (trigger) {
            Trigger.BUTTON -> null
            Trigger.USB_ATTACH -> when {
                !prefEnabled -> "auto-start is disabled in settings"
                warmAaSession -> "warm AA session is alive, leaving it alone"
                !usbAlive -> "USB is already disconnected"
                else -> null
            }
            Trigger.AFTER_SESSION -> when {
                !prefEnabled -> "auto-start is disabled in settings"
                !usbAlive -> "USB disconnected, no head unit server to restart"
                consentHold -> CONSENT_SKIP
                else -> null
            }
            Trigger.RETRY -> when {
                !prefEnabled -> "auto-start is disabled in settings"
                !usbAlive -> "USB disconnected"
                consentHold -> CONSENT_SKIP
                !lastLocked && !startPending -> "the previous attempt did not fail on a locked phone"
                else -> null
            }
        }
    }

    /** Текст заглушки на время работы автоматизации. */
    fun runningText(trigger: Trigger) = when (trigger) {
        Trigger.AFTER_SESSION -> AaBridgeCore.TEXT_RESTARTING_SERVER
        else -> AaBridgeCore.TEXT_STARTING_SERVER
    }

    /** Текст заглушки по итогу; null = менять нечего (OK: дальше идёт подключение к серверу). */
    fun resultText(trigger: Trigger, result: AutoResult): String? = when (result) {
        AutoResult.OK -> AaBridgeCore.TEXT_WAIT_FRAME
        AutoResult.LOCKED -> AaBridgeCore.TEXT_UNLOCK_PHONE
        AutoResult.CONSENT -> AaBridgeCore.TEXT_AA_CONSENT
        else -> if (trigger == Trigger.AFTER_SESSION) AaBridgeCore.TEXT_AA_LOST else AaBridgeCore.TEXT_START_SERVER
    }

    /** Сколько коннектор ждёт окончания автоматизации, прежде чем идти дальше как обычно. */
    fun waitMs(trigger: Trigger) = if (trigger == Trigger.AFTER_SESSION) 12_000L else 8_000L

    /** Пункт ENABLED_ACCESSIBILITY_SERVICES содержит нашу службу (формат «пакет/класс», через двоеточие). */
    fun isListed(setting: String?, pkg: String, cls: String): Boolean {
        if (setting.isNullOrEmpty()) return false
        val full = "$pkg/$cls"
        val shortForm = "$pkg/.${cls.removePrefix("$pkg.")}"
        return setting.split(':').any { it.equals(full, true) || it.equals(shortForm, true) }
    }
}
