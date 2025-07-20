package com.example.download_mananger.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder
import java.util.function.LongBinaryOperator

data class SpeedInfo(
    val objectId: Long,
    val speed: Double,
)

interface SpeedListener {
    fun onTotalSpeedIncreased()
}

class SpeedMonitor(val interval: Long) {
    private val listSubscribers: ConcurrentLinkedQueue<SpeedListener> = ConcurrentLinkedQueue()

    private val speedChannelDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var monitorJob: Job? = null;

    private var speedFlow: MutableStateFlow<Double> = MutableStateFlow(0.0)

    private var byteAccumulated = LongAdder()

    private var connectionCount = 0L

    suspend fun startMonitoring(start: Long) = coroutineScope {
        monitorJob = launch(speedChannelDispatcher) {
            var highestSpeed = 0.0
            var startTime = start
            while (true) {
                delay(interval)

                val endTime = System.currentTimeMillis()
                val currentSpeed = byteAccumulated.toDouble() / ((endTime - startTime) / 1000.0)
                startTime = endTime
                byteAccumulated.reset()

                this@SpeedMonitor.speedFlow.update { currentSpeed }

                val averageSpeed = currentSpeed / connectionCount

                println("speed: ${currentSpeed.toLong()}" )

                if (currentSpeed >= highestSpeed + (averageSpeed * 0.7)) {
                    highestSpeed = currentSpeed
                    notifyListeners()
                }
            }
        }
    }

    fun notifyListeners() {
        for (listener in listSubscribers) {
            listener.onTotalSpeedIncreased()
        }
    }


    fun stop() {
        println("Stopping speed monitor")
        monitorJob?.cancel()
        monitorJob = null
    }

    fun addListener(listener: SpeedListener) {
        listSubscribers.add(listener)
    }

    fun removeListener(listener: SpeedListener) {
        listSubscribers.remove(listener)
    }

    fun addByteCount(byteCount: Long) {
        byteAccumulated.add(byteCount)
    }

    suspend fun addConnectionCount() {
        withContext(speedChannelDispatcher) {
            connectionCount += 1
        }
    }

    suspend fun reduceConnectionCount() {
        withContext(speedChannelDispatcher) {
            connectionCount = (connectionCount - 1).coerceAtLeast(0)
        }
    }

    fun getSpeedFlow(): Flow<Double> {
        return speedFlow
    }
}
