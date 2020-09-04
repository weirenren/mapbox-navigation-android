package com.mapbox.navigation.base.options

import com.mapbox.navigation.testing.BuilderTest
import io.mockk.mockk
import org.junit.Test
import kotlin.reflect.KClass

class ActiveGuidanceOptionsTest : BuilderTest<ActiveGuidanceOptions, ActiveGuidanceOptions.Builder>() {
    override fun getImplementationClass(): KClass<ActiveGuidanceOptions> = ActiveGuidanceOptions::class

    override fun getFilledUpBuilder(): ActiveGuidanceOptions.Builder {
        return ActiveGuidanceOptions.Builder()
    }

    @Test
    override fun trigger() {
        // only used to trigger JUnit4 to run this class if all test cases come from the parent
    }
}
