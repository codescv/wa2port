package com.wa2port.core.update

import java.io.IOException

/** Failure categories of [AppUpdater]. */
enum class UpdateErrorCode { NETWORK, TIMEOUT, HTTP, INVALID_RESPONSE, CORRUPT_DOWNLOAD, CANCELLED, INVALID_ARGUMENT }

/** Every [AppUpdater] failure; [message] is user-facing (中文 + English). */
class UpdateException(
    val code: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int = 0,
) : IOException(message, cause)
