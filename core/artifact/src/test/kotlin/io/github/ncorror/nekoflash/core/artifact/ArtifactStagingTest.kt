package io.github.ncorror.nekoflash.core.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение о стажировке источника.
 *
 * Оно принимается **до** передачи намеренно: иначе «не умеет читать с
 * произвольного места» выяснилось бы на первом повторном запросе блока, то есть
 * уже за границей мутации.
 */
class ArtifactStagingTest {
    /** Последовательная передача стажировки не требует, каким бы ни был источник. */
    @Test
    fun aSequentialTransferNeedsNoStaging() {
        val decision = ArtifactStaging.decide(
            access = ArtifactAccess.NON_SEEKABLE,
            sizeBytes = GIB,
            randomAccessRequired = false,
            bytesAvailable = 0L,
        )

        assertTrue(decision is ArtifactStagingDecision.NotNeeded)
    }

    /** Умеющий источник отдаётся как есть: лишняя копия гигабайта никому не нужна. */
    @Test
    fun aSeekableSourceIsUsedAsItIs() {
        val decision = ArtifactStaging.decide(ArtifactAccess.SEEKABLE, GIB, true, 0L)

        assertTrue(decision is ArtifactStagingDecision.NotNeeded)
    }

    @Test
    fun aNonSeekableSourceIsStagedWhenBlocksAreAskedOutOfOrder() {
        val decision = ArtifactStaging.decide(ArtifactAccess.NON_SEEKABLE, 100L, true, 1_000L)

        assertEquals(100L, (decision as ArtifactStagingDecision.Required).bytesNeeded)
    }

    /**
     * Молчание провайдера — не разрешение считать, что он умеет.
     *
     * Непроверенная догадка выяснилась бы на первом повторном запросе блока,
     * когда отменять уже нечего.
     */
    @Test
    fun anUnknownSourceIsStagedRatherThanTrusted() {
        val decision = ArtifactStaging.decide(ArtifactAccess.UNKNOWN, 100L, true, 1_000L)

        val required = decision as ArtifactStagingDecision.Required
        assertTrue(required.reason.contains("не сообщил"))
    }

    /**
     * Не хватает места — это отдельный исход, а не `Required`, который упадёт.
     *
     * Оборвавшаяся на середине стажировка оставит половину файла и потраченное
     * время оператора, а узнать об этом можно было до первого байта.
     */
    @Test
    fun thereIsNoRoomIsAnAnswerOfItsOwn() {
        val decision = ArtifactStaging.decide(ArtifactAccess.NON_SEEKABLE, 1_000L, true, 999L)

        val noRoom = decision as ArtifactStagingDecision.NoRoom
        assertEquals(1_000L, noRoom.bytesNeeded)
        assertEquals(999L, noRoom.bytesAvailable)
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
