package com.martmists.kotpack

class RuleScope(internal val rule: String) {
    internal fun <V> DelegateRunner<V>.resolve(): V {
        return block()
    }
}
