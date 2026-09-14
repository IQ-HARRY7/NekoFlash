package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadContract
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadListener
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadProgress
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что происходит с передачей пакета в Recovery. */
public sealed interface AdbSideloadState {
    /** Не начинали. */
    public data object None : AdbSideloadState

    /**
     * Передача идёт.
     *
     * [cancellable] гаснет не по прогрессу, а по **границе мутации**, и это
     * разные моменты: граница проходится, когда первый блок уходит на провод, а
     * прогресс приходит позже — на подтверждение от Recovery. Считать её по
     * прогрессу значило бы предлагать отмену там, где отменять уже нечего.
     */
    public data class Running(
        val progress: AdbSideloadProgress,
        val cancellable: Boolean,
    ) : AdbSideloadState

    /**
     * Передача кончилась.
     *
     * [verificationPending] — не украшение: `DONEDONE` кончает **передачу**, а
     * не установку (`03` §6, инвариант 1). Пока Recovery не сказало своего,
     * исход установки неизвестен, и показывать «готово» нельзя.
     */
    public data class Finished(
        val outcome: AdbSideloadOutcome,
        val verificationPending: Boolean,
    ) : AdbSideloadState
}

/**
 * Владелец одной передачи Sideload.
 *
 * Отдельный класс по той же причине, что оболочка и файловые операции: у
 * передачи своё время жизни, и мешать его с жизнью транспорта незачем.
 *
 * Содержимое пока порождается приложением: выбор пользовательского файла — это
 * artifact source из Phase 8, и делать его наспех значило бы прятать выбор в
 * приватный каталог. Протокольный путь от этого настоящий целиком — тот же
 * драйвер, тот же поток, те же подтверждения; меняется только, откуда байты.
 */
public class AdbSideloadController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
) {
    private val mutableState = MutableStateFlow<AdbSideloadState>(AdbSideloadState.None)

    private val cancelling = AtomicBoolean(false)

    @Volatile
    private var running = false

    /** Состояние передачи. */
    public val state: StateFlow<AdbSideloadState> = mutableState.asStateFlow()

    /** Идёт ли передача прямо сейчас. */
    public val active: Boolean
        get() = running

    /**
     * Отдаёт пакет Recovery.
     *
     * Режим peer'а читается из баннера и сюда приходит готовым: догадываться о
     * нём по набору сервисов нельзя (`AdbConnectionBanner`). Отказ из-за режима
     * — это отказ **до единого байта**, и он называется, а не прячется.
     */
    public fun start(connection: AdbConnection, peerMode: AdbPeerMode, sizeBytes: Long) {
        if (running) return
        running = true
        cancelling.set(false)
        mutableState.value = AdbSideloadState.Running(nothingYet(sizeBytes), cancellable = true)
        executor.execute { transfer(connection, peerMode, sizeBytes) }
    }

    /**
     * Просит отменить.
     *
     * Просьба, а не приказ: после границы мутации сессия откажет, и это
     * правильно — устройство уже могло начать меняться.
     */
    public fun cancel() {
        cancelling.set(true)
    }

    private fun transfer(connection: AdbConnection, peerMode: AdbPeerMode, sizeBytes: Long) {
        val payload = GeneratedPayload(sizeBytes)
        val outcome = runCatching {
            connection.sideloadDriver(diagnostics).send(
                source = AdbSideloadSource { offset, length -> payload.read(offset, length) },
                totalBytes = sizeBytes,
                transportConnected = true,
                peerIsSideload = peerMode == AdbPeerMode.SIDELOAD,
                listener = Watcher(),
                cancelRequested = cancelling::get,
            )
        }.getOrElse { error ->
            AdbSideloadOutcome.Failed(
                AdbSideloadFailure.FILE,
                error.message ?: error.javaClass.simpleName,
            )
        }
        mutableState.value = AdbSideloadState.Finished(
            outcome = outcome,
            verificationPending = AdbSideloadContract.requiresVerification(outcome),
        )
        running = false
    }

    /** Переносит события передачи в состояние экрана. */
    private inner class Watcher : AdbSideloadListener {
        @Volatile
        private var cancellable = true

        override fun onProgress(progress: AdbSideloadProgress) {
            mutableState.value = AdbSideloadState.Running(progress, cancellable)
        }

        override fun onMutationBoundary() {
            cancellable = false
            // Кнопка гаснет сразу, а не со следующим подтверждением: между
            // границей и первым подтверждением проходит целый блок, и всё это
            // время отмена была бы обещанием, которого никто не сдержит.
            val shown = mutableState.value
            if (shown is AdbSideloadState.Running) {
                mutableState.value = shown.copy(cancellable = false)
            }
        }
    }

    private fun nothingYet(sizeBytes: Long): AdbSideloadProgress {
        val block = AdbSideloadContract.BLOCK_SIZE_BYTES.toLong()
        return AdbSideloadProgress(
            servedBytes = 0L,
            uniqueBytes = 0L,
            totalBytes = sizeBytes,
            uniqueBlocks = 0,
            totalBlocks = if (sizeBytes <= 0L) 0 else ((sizeBytes + block - 1) / block).toInt(),
        )
    }
}
