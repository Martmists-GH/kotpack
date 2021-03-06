package com.martmists.kotpack

class RuleScope(internal val rule: String) {
    internal inline fun <V> DelegateRunner<V>.resolve(): V {
        return block()
    }
}
