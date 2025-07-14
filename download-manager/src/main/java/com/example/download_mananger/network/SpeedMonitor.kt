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
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

data class SpeedInfo(
    val objectId: Long,
    val speed: Double,
)

interface SpeedListener {
    fun onTotalSpeedIncreased()
}

class SpeedMonitor(val interval: Long) {
    private val listSubscribers: ArrayList<SpeedListener> = arrayListOf()

    private val speedChannel = Channel<SpeedInfo>()

    private val subcriberDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val speedChannelDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var monitorJob: Job? = null;

    private var currentSpeed: AtomicLong = AtomicLong(0)

    private var ids: ConcurrentSkipListSet<Long> = ConcurrentSkipListSet()
    private var finishedIds: ConcurrentSkipListSet<Long> = ConcurrentSkipListSet()

    suspend fun startMonitoring() = coroutineScope {
        monitorJob = launch(speedChannelDispatcher) {
            val listSpeed: ArrayList<SpeedInfo> = arrayListOf()
            var highestSpeed = 0.0
            while (true) {
                delay(interval);
                listSpeed.clear()
                while (!speedChannel.isEmpty) {
                    val speedResult = speedChannel.receiveCatching()
                    listSpeed.add(speedResult.getOrThrow())
                }
                val currentSpeed = listSpeed.sumOf { it.speed }
                this@SpeedMonitor.currentSpeed.set(currentSpeed.toLong())

                val averageSpeed = currentSpeed / ids.size
                ids.removeAll(finishedIds)
                finishedIds.clear()

                println("total speed is ${currentSpeed / 1024 / 1024}")

                if (currentSpeed >= highestSpeed + (averageSpeed * 0.5)) {
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
        ids.add(objectId);
        speedChannel.send(SpeedInfo(objectId, speed));
    }

    fun setIdIsFinished(objectId: Long) {
        finishedIds.add(objectId)
    }

    fun getCurrentSpeed(): Long {
        return currentSpeed.get();
    }
}
