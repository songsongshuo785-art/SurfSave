package com.myAllVideoBrowser.util

import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DisposableJobRegistry {

    private val jobs = ConcurrentHashMap<String, JobHolder>()
    private val lock = Any()

    fun tryRegister(key: String, disposableFactory: (holder: JobHolder) -> Disposable): Boolean {
        val holder = JobHolder()

        synchronized(lock) {
            val existing = jobs[key]
            if (existing != null && !existing.isFinished()) {
                return false
            }
            jobs[key] = holder
        }

        val disposable = try {
            disposableFactory(holder)
        } catch (e: Exception) {
            synchronized(lock) { jobs.remove(key, holder) }
            throw e
        }

        synchronized(lock) {
            if (holder.isCancelled() || jobs[key] !== holder) {
                disposable.dispose()
                jobs.remove(key, holder)
                return true
            }
            holder.setDisposable(disposable)
        }

        return true
    }

    fun finish(key: String, holder: JobHolder) {
        jobs.remove(key, holder)
    }

    fun cancelAll() {
        synchronized(lock) {
            val snapshot = jobs.values.toList()
            jobs.clear()
            snapshot.forEach { it.cancel() }
        }
    }

    fun isRunning(key: String): Boolean {
        val holder = jobs[key]
        return holder != null && !holder.isFinished()
    }

    fun size(): Int = jobs.size

    class JobHolder {
        private val cancelled = AtomicBoolean(false)
        @Volatile
        private var disposable: Disposable? = null

        fun setDisposable(d: Disposable) {
            disposable = d
        }

        fun cancel() {
            cancelled.set(true)
            disposable?.dispose()
        }

        fun isCancelled(): Boolean = cancelled.get()

        fun isFinished(): Boolean = cancelled.get() || (disposable?.isDisposed == true)
    }
}
