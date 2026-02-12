package util

import io.github.oshai.kotlinlogging.KotlinLogging

inline fun <reified T : Any> T.log() = KotlinLogging.logger {}
