/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.logging

import android.util.Log

object Logger {
    const val TAG = "TTESimulator"

    sealed class LogLevel(val priority: Int) {
        object Debug : LogLevel(Log.DEBUG)

        object Info : LogLevel(Log.INFO)

        object Warning : LogLevel(Log.WARN)

        object Error : LogLevel(Log.ERROR)

        object Verbose : LogLevel(Log.VERBOSE)
    }

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }

    fun e(message: String, throwable: Throwable) {
        Log.e(TAG, "fatal: $message", throwable)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun w(message: String, throwable: Throwable) {
        Log.w(TAG, message, throwable)
    }

    fun v(message: String) {
        Log.v(TAG, message)
    }

    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        when (level) {
            is LogLevel.Debug ->
                if (throwable != null) Log.d(TAG, message, throwable) else Log.d(TAG, message)
            is LogLevel.Info ->
                if (throwable != null) Log.i(TAG, message, throwable) else Log.i(TAG, message)
            is LogLevel.Warning ->
                if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
            is LogLevel.Error ->
                if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
            is LogLevel.Verbose ->
                if (throwable != null) Log.v(TAG, message, throwable) else Log.v(TAG, message)
        }
    }

    fun logIf(level: LogLevel, condition: Boolean = true, messageProvider: () -> String) {
        if (condition && Log.isLoggable(TAG, level.priority)) {
            log(level, messageProvider())
        }
    }
}
