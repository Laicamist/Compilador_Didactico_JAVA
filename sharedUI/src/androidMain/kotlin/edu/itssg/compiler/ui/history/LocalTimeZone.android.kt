package edu.itssg.compiler.ui.history

import java.util.TimeZone

actual fun localUtcOffsetMillis(epochMillis: Long): Long = TimeZone.getDefault().getOffset(epochMillis).toLong()
