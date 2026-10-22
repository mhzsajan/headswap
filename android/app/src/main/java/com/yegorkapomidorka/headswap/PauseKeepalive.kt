package com.yegorkapomidorka.headswap

/**
 * Выученные настройки PAUSE по ключу "канал STATISTIC_INFO/транспорт".
 * Реализует AaPrefs; в unit-тестах - простая заглушка.
 */
interface PauseMemory {
    fun pauseHeartbeatLearned(key: String): Boolean
    fun learnPauseHeartbeat(key: String)
}

/**
 * Чем держать паузу и когда запоминать ГУ. Чистые функции без Android: поведение проверяется unit-тестами.
 *
 * Зачем: список ГУ, которым на PAUSE нужен VIDEO_HEARTBEAT, растёт с каждой машиной (h93, h222, h224, h425, h211...).
 * Вместо новой строки на каждое ГУ запоминаем канал, который уже оборвался на паузе с пустым VIDEO_DATA:
 * следующая сессия с этим ГУ сразу шлёт heartbeat.
 *
 * Ключ включает транспорт: один и тот же канал на USB и на воздухе может вести себя по-разному
 * (Desay G6S: по USB держит пустой VIDEO_DATA, на воздухе - только heartbeat, h211).
 */
object PauseKeepalivePolicy {
    /**
     * Порог обучения. ГУ рвут связь через 3,6-6 с паузы; закрытие раньше похоже на отсоединение кабеля
     * или выход из CarLife, а не на неудачный keepalive.
     */
    const val LEARN_MIN_PAUSE_MS = 3_500L

    /** Чем держать PAUSE: heartbeat (VW, Desay на воздухе, выученные) или пустой VIDEO_DATA. */
    fun needsHeartbeat(
        knownVw: Boolean,
        air: Boolean,
        vwBt1420: Boolean,
        desayG6S: Boolean,
        learned: Boolean,
    ): Boolean = knownVw || learned || (air && (vwBt1420 || desayG6S))

    /**
     * Запоминать канал можно, только если:
     * - это не «Стоп» пользователя (тогда обрыв ожидаемый);
     * - heartbeat ещё не слали (иначе причина обрыва другая, и менять нечего);
     * - канал известен (иначе нечего запоминать);
     * - Desay G6S по USB: ему подходит пустой VIDEO_DATA, heartbeat там не проверен;
     * - пауза длилась не меньше [LEARN_MIN_PAUSE_MS].
     */
    fun shouldLearn(
        closedWhilePausedMs: Long,
        usedHeartbeat: Boolean,
        userStop: Boolean,
        channel: String?,
        desayUsb: Boolean,
    ): Boolean = !userStop && !usedHeartbeat && !desayUsb &&
        !channel.isNullOrBlank() && closedWhilePausedMs >= LEARN_MIN_PAUSE_MS
}
