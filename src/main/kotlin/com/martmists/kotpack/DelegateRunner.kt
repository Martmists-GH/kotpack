package com.martmists.kotpack

import kotlin.reflect.KProperty

class DelegateRunner<V>(val block: RuleScope.() -> V) {
    private lateinit var func: () -> V
    operator fun getValue(thisRef: Any?, prop: KProperty<*>): () -> V {
        return func
    }

    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): DelegateRunner<V> {
        func = { RuleScope(prop.name).block() }
        return this
    }
}
