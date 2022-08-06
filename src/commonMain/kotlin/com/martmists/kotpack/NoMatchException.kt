package com.martmists.kotpack

class NoMatchException(internal val rule: String, parser: GrammarParser<*>, message: String, private val prev: NoMatchException? = null) : Exception(parser.getErrorMessage(rule, message), prev) {
    private val pos = parser.getPos()
    internal fun depth(): Int {
        return minOf(-pos, prev?.depth() ?: 0)
    }
}
