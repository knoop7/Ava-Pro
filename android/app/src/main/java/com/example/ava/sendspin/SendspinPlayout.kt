package com.example.ava.sendspin

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object SendspinPlayout {
    private val threadCounter = AtomicInteger(0)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            object : Thread(runnable, "SendspinPlayout-${threadCounter.incrementAndGet()}") {
                override fun run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    super.run()
                }
            }
        }
    )

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
