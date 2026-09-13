package io.github.ncorror.nekoflash.payload

import java.io.OutputStream
import java.security.MessageDigest

/**
 * Приёмник, который считает байты и отпечаток, но не хранит содержимое.
 *
 * Зеркало [GeneratedPayload]: тот порождает содержимое, не собирая его целиком,
 * этот принимает содержимое, не собирая его целиком. Причина та же и здесь
 * серьёзнее: раздел бывает на несколько гигабайт, и положить его в кучу значит
 * убить приложение на первом же большом чтении.
 *
 * Приём тот же, что уже принят для ADB `pull` (`07` §6.32), где прочитанный
 * файл подтверждается количеством байт и SHA-256, а не сохранением: сохранение
 * в пользовательское место требует artifact sink из Phase 8.
 *
 * Отпечаток здесь доказывает **прочитанное**, а не содержимое раздела на
 * устройстве: совпадение двух чтений подряд говорит, что путь чтения
 * устойчив, и ничего не говорит о том, что именно лежит в разделе.
 */
internal class DigestingSink : OutputStream() {
    private val digest = MessageDigest.getInstance(ALGORITHM)

    /** Сколько байт принято. */
    var bytes: Long = 0L
        private set

    override fun write(value: Int) {
        digest.update(value.toByte())
        bytes += 1
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        digest.update(source, offset, length)
        bytes += length
    }

    /** Отпечаток принятого. Вызывать один раз: `MessageDigest` после этого сбрасывается. */
    fun sha256(): String = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ALGORITHM = "SHA-256"
    }
}
