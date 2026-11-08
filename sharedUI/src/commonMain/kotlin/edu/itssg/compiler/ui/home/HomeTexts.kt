package edu.itssg.compiler.ui.home

import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.viewmodel.TourStep

/** Botón de tema del Inicio: Sistema → Claro → Oscuro → Sistema. */
internal fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

/** Texto de cada globo del tour de bienvenida. */
internal fun tourText(step: TourStep): String = when (step) {
    TourStep.PHASE_LINE ->
        "Un compilador trabaja por fases: léxico, sintáctico, árbol, semántico, código intermedio y optimización. " +
            "La app muestra el resultado de cada una."
    TourStep.NEW_WORK ->
        "Empieza aquí: escribe o pega tu programa en Java (GLC v7) y compílalo todo o paso a paso para ver cada fase."
    TourStep.HELP_MENU ->
        "¿Dudas sobre una fase? Cada tema abre una hoja con la explicación y el ejemplo int x = 5 + 3; compilado de verdad."
    TourStep.HISTORY ->
        "Cada trabajo que guardes queda en el Historial, con sus errores y su código intermedio. Desde ahí lo reabres o lo exportas."
}
