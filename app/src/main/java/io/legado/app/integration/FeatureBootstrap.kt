package io.legado.app.integration

import io.legado.features.adapter.FeatureAssembly

/** Stateless feature assembly is also available after service/process recreation. */
object FeatureBootstrap {
    fun start(application: android.app.Application) = io.legado.app.integration.source.NgExtensionBootstrap.start(application)
    val features: FeatureAssembly = FeatureAssembly
}
