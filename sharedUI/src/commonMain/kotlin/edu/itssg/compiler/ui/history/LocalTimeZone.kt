package edu.itssg.compiler.ui.history

/**
 * Diferencia en milisegundos entre la hora local del dispositivo y UTC en el instante [epochMillis] (incluye el
 * horario de verano si aplica). Así las fechas del Historial se muestran en la zona horaria del usuario sin agregar
 * una biblioteca de fechas.
 */
expect fun localUtcOffsetMillis(epochMillis: Long): Long
