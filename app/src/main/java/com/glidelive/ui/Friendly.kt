package com.glidelive.ui

/** Mensajes en español sencillo para los errores que le pueden pasar a quien hace un cuento. */
object Friendly {
    fun message(t: Throwable): String = when {
        t is OutOfMemoryError -> "El teléfono se quedó sin memoria con esto. Prueba con un cuento más corto o cierra otras apps."
        t is java.net.UnknownHostException || t is java.net.SocketTimeoutException || t is java.net.ConnectException ->
            "No hay conexión a internet. Revisa el WiFi y vuelve a intentar."
        t is java.io.IOException && (t.message ?: "").contains("ENOSPC", true) -> "No queda espacio en el teléfono. Borra algo y vuelve a intentar."
        t is SecurityException -> "La app no tiene permiso para hacer eso."
        else -> "Algo salió mal. Vuelve a intentarlo; tu cuento está guardado."
    }
}
