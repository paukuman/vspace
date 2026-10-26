package com.example.vspace

import java.lang.reflect.Field

object Refl {
    fun field(c0: Class<*>, name: String): Field {
        var c: Class<*>? = c0
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    fun get(o: Any, name: String): Any? = field(o.javaClass, name).get(o)
    fun set(o: Any, name: String, v: Any?) { field(o.javaClass, name).set(o, v) }
}
