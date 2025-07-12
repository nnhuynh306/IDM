package com.example.download_mananger.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class SpeedInfo(
    val objectId: Long,
    val speed: Double,
)

interface SpeedListener {
    fun onTotalSpeedIncreased()
}

class SpeedMonitor {
    private val listSubscribers: ArrayList<SpeedListener> = arrayListOf()

    private val speedChannel = Channel<SpeedInfo>()

    private val subcriberDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val speedChannelDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var monitorJob: Job? = null;

    private var currentSpeed: AtomicLong = AtomicLong(0)

    suspend fun startMonitoring() = coroutineScope {
        monitorJob = launch(speedChannelDispatcher) {
            val listSpeed: ArrayList<SpeedInfo> = arrayListOf()
            var highestSpeed = 0.0
            while (true) {
                delay(1000);
                listSpeed.clear()
                while (!speedChannel.isEmpty) {
                    val speedResult = speedChannel.receiveCatching()
                    listSpeed.add(speedResult.getOrThrow())
                }
                val currentSpeed = listSpeed.sumOf { it.speed }
                this@SpeedMonitor.currentSpeed.set(currentSpeed.toLong())
                if (currentSpeed > highestSpeed * 1.5) {
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
        monitorJob?.cancel()
        monitorJob = null
    }

    suspend fun addListener(listener: SpeedListener) {
        withContext(subcriberDispatcher) {
            listSubscribers.add(listener)
        }
    }

    suspend fun removeListener(listener: SpeedListener) {
        withContext(subcriberDispatcher) {
            listSubscribers.remove(listener)
        }
    }

    suspend fun updateSpeedInfo(objectId: Long, speed: Double) {
        speedChannel.send(SpeedInfo(objectId, speed));
    }

    fun getCurrentSpeed(): Long {
        return currentSpeed.get();
    }
}
