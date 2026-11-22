package com.example

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Plays the part of the stockbroker: answers every order after a short wait.
// (Tests call AccountStore.settle directly, so they do not need to wait.)
object Broker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedule(userId: Int, orderId: Int) {
        scope.launch {
            delay(4_000)
            AccountStore.settle(userId, orderId)
        }
    }
}

