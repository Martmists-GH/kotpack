package com.martmists.kotpack

import java.util.*

abstract class GrammarParser<T>(internal val input: String) {
    private val errors = PriorityQueue<NoMatchException> { a, b ->
        a.depth().compareTo(b.depth())
    }

    // The code for `memo` and `memoLeft` was more-or-less adapted from Guido van Rossum's implementation on
    // his blog on PEG parsers: https://medium.com/@gvanrossum_83706/peg-parsing-series-de5d41b2ed60
    private data class Memo(val res: Any, val index: Int)
    protected fun <V: Any> memo(func: RuleScope.() -> V) : RuleScope.() -> V {
        val memo = mutableMapOf<Int, Memo>()

        return {
            val p = pos
            if (p in memo) {
                val m = memo[p]!!.let { if (it.res is NoMatchException) throw it.res else it }
                pos = m.index
                m.res as V
            } else {
                val res = func()
                memo[p] = Memo(res, pos)
                res
            }
        }
    }

    protected fun <V: Any> memoLeft(func: RuleScope.() -> V) : RuleScope.() -> V {
        val memo = mutableMapOf<Int, Memo>()

        return {
            val p = pos
            if (p in memo) {
                val m = memo[pos]!!.let { if (it.res is NoMatchException) throw it.res else it }
                pos = m.index
                m.res as V
            } else {
                var lastres: Any = NoMatchException(rule, this@GrammarParser, "Exception in memoLeft happened; you should never see this! Open an issue if you do.")
                var lastpos = -1
                memo[p] = Memo(lastres, lastpos)
                while (true) {
                    pos = p
                    val res = try {
                        func()
                    } catch (e: NoMatchException) {
                        e
                    }
                    val endpos = pos
                    if (endpos <= lastpos) {
                        break
                    }
                    memo[p] = Memo(res, endpos)
                    lastres = res
                    lastpos = endpos
                }
                lastres.also { pos = lastpos; if (it is NoMatchException) throw it } as V
            }
        }
    }

    internal var pos = 0

    private val saved = Stack<Int>()

    private val remaining: String
        get() = input.substring(pos)

    private fun save() {
        saved.push(pos)
    }
    private fun restore() {
        pos = saved.pop()
    }
    private fun drop() {
        saved.pop()
    }

    @PublishedApi
    internal fun getErrorMessage(rule: String, message: String): String {
        val parts = remaining.split('\n', limit=2)
        val nextRemaining = parts.last()
        val currentLine: String
        val errorPos: Int
        if (nextRemaining == input) {
            currentLine = input
            errorPos = pos
        } else {
            val inputUntilEOL = input.substring(0, input.length - nextRemaining.length - 1)
            currentLine = inputUntilEOL.split('\n').last()
            val lineOffset = input.length - (nextRemaining.length + currentLine.length + 1)
            errorPos = pos - lineOffset
        }
        return "Error in rule '$rule' at \n $currentLine\n${" ".repeat(errorPos)}/\u200B\\\nError: $message"
    }

    protected fun RuleScope.throwExc(message: String, cause: NoMatchException? = null): Nothing = throwExc(rule, message, cause)
    protected fun throwExc(rule: String, message: String, cause: NoMatchException? = null): Nothing {
        val err = NoMatchException(rule, this, message, cause)
        errors.add(err)
        throw err
    }

    protected fun eoi() {
        if (remaining.isNotEmpty()) {
            throwExc("eoi", "Expected end of input")
        }
    }

    protected fun RuleScope.char(c: Char) = this@GrammarParser.char(c).resolve()
    protected fun char(c: Char) = DelegateRunner {
        if (!remaining.startsWith(c)) {
            throwExc(rule, "Expected '$c'")
        }
        pos++
        c
    }

    protected fun RuleScope.string(s: String) = this@GrammarParser.string(s).resolve()
    protected fun <V: Any> string(s: String, block: RuleScope.(String) -> V) = sequence { block(string(s)) }
    protected fun string(s: String) = DelegateRunner {
        if (!remaining.startsWith(s)) {
            throwExc(rule, "Expected '$s'")
        }
        pos += s.length
        s
    }

    protected fun RuleScope.regex(r: String) = this@GrammarParser.regex(r).resolve()
    protected fun <V: Any> regex(r: String, block: RuleScope.(String) -> V) = sequence { block(regex(r)) }
    protected fun regex(r: String) = DelegateRunner {
        val match = Regex(r).matchAt(remaining, 0) ?: throwExc(rule, "Expected matching regex /${r.replace("\n", "\\n")}/")
        match.groups[0]!!.value.also {
            pos += it.length
        }
    }

    protected fun <V: Any> RuleScope.sequence(block: RuleScope.() -> V) = this@GrammarParser.sequence(block).resolve()
    protected fun <V: Any> sequence(block: RuleScope.() -> V) = DelegateRunner {
        try {
            block()
        } catch (e: NoMatchException) {
            if (e.rule == rule) {
                throw e
            }
            throwExc(rule, "Error parsing sequence", e)
        }
    }

    protected fun <V: Any> RuleScope.first(vararg blocks: () -> () -> V) = this@GrammarParser.first(*blocks).resolve()
    protected fun <V: Any> first(vararg blocks: () -> () -> V) = DelegateRunner {
        val errors = mutableListOf<NoMatchException>()
        for (block in blocks) {
            try {
                save()
                return@DelegateRunner block()().also { drop() }
            } catch (e: NoMatchException) {
                errors.add(e)
                restore()
            } catch (e: StackOverflowError) {
                throw RuntimeException("Error: Non-memoized recursion detected while parsing rule '$rule'")
            }
        }
        throwExc(rule, "Expected one of: [${errors.joinToString(transform = NoMatchException::rule)}]", errors.minByOrNull(NoMatchException::depth))
    }

    protected fun <V: Any> RuleScope.optional(block: RuleScope.() -> V) = this@GrammarParser.optional(block).resolve()
    protected fun <V: Any> optional(block: RuleScope.() -> V) = DelegateRunner(memo {
        try {
            save()
            Optional.of(block()).also { drop() }
        } catch (e: NoMatchException) {
            restore()
            Optional.empty()
        }
    })

    protected fun <V: Any> RuleScope.zeroOrMore(block: RuleScope.() -> V) = this@GrammarParser.zeroOrMore(block).resolve()
    protected fun <V: Any> zeroOrMore(block: RuleScope.() -> V) = DelegateRunner<List<V>> {
        val results = mutableListOf<V>()
        while (remaining.isNotEmpty()) {
            try {
                save()
                results.add(block()).also { drop() }
            } catch (e: NoMatchException) {
                restore()
                break
            }
        }
        results
    }

    protected fun <V: Any> RuleScope.oneOrMore(block: RuleScope.() -> V) = this@GrammarParser.oneOrMore(block).resolve()
    protected fun <V: Any> oneOrMore(block: RuleScope.() -> V) = DelegateRunner<List<V>> {
        val results = mutableListOf<V>()
        val errors = mutableListOf<NoMatchException>()
        while (remaining.isNotEmpty()) {
            try {
                save()
                results.add(block()).also { drop() }
            } catch (e: NoMatchException) {
                errors.add(e)
                restore()
                break
            }
        }
        if (results.isEmpty()) {
            throwExc(rule, "Expected at least one match, got 0", errors.first())
        }
        results
    }

    protected fun RuleScope.whitespace(optional: Boolean = false) = this@GrammarParser.whitespace(optional).resolve()
    protected fun whitespace(optional: Boolean = true) = regex("[ \t]" + if (optional) "*" else "+")
    protected fun <T: Any> whitespace(optional: Boolean = true, block: () -> T) : T {
        val whitespace by whitespace(optional)
        whitespace()
        val res = block()
        whitespace()
        return res
    }

    fun tryParse() = try {
        root()
    } catch (e: NoMatchException) {
        throw errors.peek()
    }

    protected abstract val root: () -> T
}
