package com.myAllVideoBrowser.util

import io.reactivex.rxjava3.disposables.Disposable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisposableJobRegistryTest {

    @Test
    fun tryRegister_sameKeyOnlyRegistersOnce() {
        val registry = DisposableJobRegistry()

        val first = registry.tryRegister("url1") { Disposable.empty() }
        val second = registry.tryRegister("url1") { Disposable.empty() }

        assertTrue(first)
        assertFalse(second)
        assertTrue(registry.isRunning("url1"))
    }

    @Test
    fun tryRegister_allowsReRegistrationAfterFinish() {
        val registry = DisposableJobRegistry()
        var capturedHolder: DisposableJobRegistry.JobHolder? = null

        registry.tryRegister("url1") { holder ->
            capturedHolder = holder
            Disposable.empty()
        }
        registry.finish("url1", capturedHolder!!)

        val second = registry.tryRegister("url1") { Disposable.empty() }
        assertTrue(second)
    }

    @Test
    fun finish_withHolder_doesNotRemoveNewerTask() {
        val registry = DisposableJobRegistry()
        var oldHolder: DisposableJobRegistry.JobHolder? = null

        registry.tryRegister("url1") { holder ->
            oldHolder = holder
            Disposable.empty()
        }

        // Simulate old task finishing after cancelAll + re-register
        registry.cancelAll()
        registry.tryRegister("url1") { Disposable.empty() }

        // Old holder's finish should NOT remove the new task
        registry.finish("url1", oldHolder!!)

        assertTrue(registry.isRunning("url1"))
    }

    @Test
    fun cancelAll_disposesAllAndClearsRegistry() {
        val registry = DisposableJobRegistry()
        val disposables = mutableListOf<Disposable>()

        repeat(5) { i ->
            registry.tryRegister("url$i") { _ ->
                Disposable.empty().also { disposables.add(it) }
            }
        }

        registry.cancelAll()

        assertEquals(0, registry.size())
        disposables.forEach { assertTrue(it.isDisposed) }
    }

    @Test
    fun cancelAll_disposesRealDisposableNotJustPlaceholder() {
        val registry = DisposableJobRegistry()
        var realDisposable: Disposable? = null

        registry.tryRegister("url1") { _ ->
            Disposable.empty().also { realDisposable = it }
        }

        registry.cancelAll()

        assertTrue(realDisposable!!.isDisposed)
    }

    @Test
    fun tryRegister_allowsReRegistrationAfterDisposed() {
        val registry = DisposableJobRegistry()
        var disposable: Disposable? = null

        registry.tryRegister("url1") { _ ->
            Disposable.empty().also { disposable = it }
        }

        disposable!!.dispose()

        val second = registry.tryRegister("url1") { _ -> Disposable.empty() }
        assertTrue(second)
    }

    @Test
    fun tryRegister_cleansUpOnFactoryException() {
        val registry = DisposableJobRegistry()

        try {
            registry.tryRegister("url1") { _ ->
                throw RuntimeException("factory failed")
            }
        } catch (_: RuntimeException) {
        }

        // Should not be stuck as "running"
        assertFalse(registry.isRunning("url1"))

        // Should allow re-registration
        val second = registry.tryRegister("url1") { _ -> Disposable.empty() }
        assertTrue(second)
    }

    @Test
    fun cancelAll_duringFactory_disposesReturnedDisposable() {
        val registry = DisposableJobRegistry()
        var factoryDisposable: Disposable? = null

        registry.tryRegister("url1") { _ ->
            // Simulate cancelAll being called while factory is executing
            registry.cancelAll()
            Disposable.empty().also { factoryDisposable = it }
        }

        // The disposable returned by factory should be disposed
        // because cancelAll cleared the holder before factory returned
        assertTrue(factoryDisposable!!.isDisposed)
        assertFalse(registry.isRunning("url1"))
    }
}
