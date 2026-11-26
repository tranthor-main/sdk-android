package com.tranthor.sdk

import kotlin.coroutines.Continuation
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Code compiled against 0.2.x must still link: the old compiled signatures stay in the library. */
class BinaryCompatibilityTest {
    private val strings = String::class.java
    private val map = Map::class.java

    @Test fun theOldIdentifySignatureAndItsDefaultsBridgeStillExist() {
        val identify = TranthorSDK::class.java.getMethod("identify", strings, strings, map, strings, strings, map, Continuation::class.java)
        assertNotNull(identify)
        // Kotlin callers that omit arguments compiled against `identify$default` of 0.2.x.
        assertNotNull(TranthorSDK::class.java.getMethod("identify\$default", TranthorSDK::class.java, strings, strings, map, strings, strings, map,
            Continuation::class.java, Int::class.javaPrimitiveType, Any::class.java))
    }

    @Test fun theElevenArgumentConfigurationConstructorForJavaStillExists() {
        assertNotNull(Configuration::class.java.getConstructor(strings, strings, Number::class.java, Number::class.java, Number::class.java, Number::class.java,
            Boolean::class.javaPrimitiveType, strings, java.io.File::class.java, Int::class.javaPrimitiveType, Function1::class.java))
    }
}
