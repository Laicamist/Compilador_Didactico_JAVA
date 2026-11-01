package com.laicamist.proyecto_automatas

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform