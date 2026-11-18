package com.tranthor.sdk

/**
 * Marks plumbing between tranthor-sdk and tranthor-push. Host apps should not call it, and it can
 * change in any release.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "This API is internal to the Tranthor SDK modules and can change without notice.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
public annotation class InternalTranthorApi
