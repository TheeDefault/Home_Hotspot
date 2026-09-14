package com.homehotspot

import android.content.Context

/**
 * Backward-compatible delegator for executing the HOME arrival action sequence.
 * Hands off execution directly to [ActionSequenceService].
 */
object ActionSequenceExecutor {
    fun executeEnterSequence(context: Context, onComplete: () -> Unit = {}) {
        ActionSequenceService.startActionSequence(context)
        onComplete()
    }
}

