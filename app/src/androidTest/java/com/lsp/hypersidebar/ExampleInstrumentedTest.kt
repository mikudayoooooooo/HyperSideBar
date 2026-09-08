package com.lsp.hypersidebar

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // 迭代五批次 0：断言的是 APK 身份 applicationId（已迁 io.github.*），
        // 与源码 namespace（com.lsp.hypersidebar）解耦
        assertEquals("io.github.mikudayoooooooo.hypersidebar", appContext.packageName)
    }
}