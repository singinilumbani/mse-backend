package com.example

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Plays the part of the broker's compliance team: looks at a submitted registration after a short wait.
// (Tests call KycStore.autoReview directly, so they do not need to wait.)
object KycReviewer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedule(userId: Int) {
        scope.launch {
            delay(5_000)
            KycStore.autoReview(userId)
        }
    }
}

