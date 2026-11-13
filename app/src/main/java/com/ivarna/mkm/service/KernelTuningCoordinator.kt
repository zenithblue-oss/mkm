package com.ivarna.mkm.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thrown by [KernelTuningCoordinator.withUserMutation] when Game Boost owns tuning. */
class GameBoostOwnsTuningException : IllegalStateException("Game Boost is active; disable it before changing tuning manually")

/** Process-wide lock for every CPU/GPU/thermal mutation touching shared kernel state. */
object KernelTuningCoordinator {
    private val mutationMutex = Mutex()

    suspend fun <T> withMutation(block: suspend () -> T): T = mutationMutex.withLock { block() }

    /**
     * Manual (user) tuning: re-checks Game Boost ownership while holding the lock,
     * so an enable cannot slip in between the caller's check and the write.
     * @throws GameBoostOwnsTuningException when Game Boost owns tuning.
     */
    suspend fun <T> withUserMutation(
        ownsTuning: () -> Boolean = GameBoostRegistry::ownsTuning,
        block: suspend () -> T
    ): T = mutationMutex.withLock {
        if (ownsTuning()) throw GameBoostOwnsTuningException()
        block()
    }
}
