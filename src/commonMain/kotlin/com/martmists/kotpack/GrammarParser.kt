package com.martmists.kotpack

import com.martmists.commons.datastructures.*

abstract class GrammarParser<T> {
    protected lateinit var input: String
    protected var pos = 0
    internal fun getPos() = pos

    private val errors = MinHeap(NoMatchException::depth)

    // The code for `memo` and `memoLeft` was more-or-less adapted from Guido van Rossum's implementation on
    // his blog on PEG parsers: https://medium.com/@gvanrossum_83706/peg-parsing-series-de5d41b2ed60
    private data class Memo(val res: Any?, val index: Int)
    protected fun <V> memoImpl(func: RuleScope.() -> V) : RuleScope.() -> V {
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

    protected fun <V> memoLeftImpl(func: RuleScope.() -> V) : RuleScope.() -> V {
        val memo = mutableMapOf<Int, Memo>()

        return {
            val p = pos
            if (p in memo) {
                val m = memo[pos]!!.let { if (it.res is NoMatchException) throw it.res else it }
                pos = m.index
                m.res as V
            } else {
                var lastres: Any? = NoMatchException(rule, this@GrammarParser, "Exception in memoLeft happened; you should never see this! Open an issue if you do.")
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
    private val saved = Stack<Int>()

    protected val remaining: String
        get() = input.substring(pos)

    protected fun save() {
        saved.push(pos)
    }
    protected fun restore() {
        pos = saved.pop()
    }
    protected fun drop() {
        saved.pop()
    }

    @PublishedApi
    internal fun getErrorMessage(rule: String, message: String): String {
        val parsed = input.substring(0, pos)
        val lineIndex = parsed.count { it == '\n' }
        val lines = input.split('\n', limit = lineIndex + 2)
        val context = if (lines.size == 1) {
            listOf(lines[0])
        } else {
            lines.subList(maxOf(0, lineIndex - 2), lineIndex + 1)
        }
        val startStr = parsed.split('\n').last()
        val offset = startStr.length
        return "Error in rule '$rule' at line ${lineIndex + 1}: \n ${context.joinToString("\n ")}\n${" ".repeat(offset)}/\u200B\\\nError: $message"
    }

    protected fun RuleScope.throwExc(message: String, cause: NoMatchException? = null): Nothing = throwExc(rule, message, cause)
    protected fun throwExc(rule: String, message: String, cause: NoMatchException? = null): Nothing {
        val err = NoMatchException(rule, this, message, cause)
        errors.push(err)
        throw err
    }

    protected fun eoi() {
        if (remaining.isNotEmpty()) {
            throwExc("eoi", "Expected end of input")
        }
    }

    protected fun <V> DelegateRunner<V>.memoLeft(): DelegateRunner<V> {
        block = memoLeftImpl(block)
        return this
    }
    protected fun <V> memoLeft(block: RuleScope.() -> V) = DelegateRunner(memoLeftImpl {
        block()
    })

    protected fun <V> DelegateRunner<V>.memo(): DelegateRunner<V> {
        block = memoImpl(block)
        return this
    }
    protected fun <V> memo(block: RuleScope.() -> V) = DelegateRunner(memoImpl {
        block()
    })

    protected fun RuleScope.char(c: Char) = this@GrammarParser.char(c).resolve()
    protected fun char(c: Char) = DelegateRunner {
        if (!remaining.startsWith(c)) {
            throwExc(rule, "Expected '$c'")
        }
        pos++
        c
    }

    protected fun RuleScope.chars(cs: CharSequence) = this@GrammarParser.chars(cs).resolve()
    protected fun chars(cs: CharSequence) = DelegateRunner {
        first(
            *cs.map { firstBlock { char(it) } }.toTypedArray()
        )
    }

    protected fun RuleScope.string(s: String) = this@GrammarParser.string(s).resolve()
    protected fun <V> string(s: String, block: RuleScope.(String) -> V) = sequence { block(string(s)) }
    protected fun string(s: String) = DelegateRunner {
        if (!remaining.startsWith(s)) {
            throwExc(rule, "Expected '$s'")
        }
        pos += s.length
        s
    }

    protected fun RuleScope.regex(r: String) = this@GrammarParser.regex(r).resolve()
    protected fun <V> regex(r: String, block: RuleScope.(String) -> V) = sequence { block(regex(r)) }
    protected fun regex(r: String) = DelegateRunner {
        val match = Regex(r).matchAt(remaining, 0) ?: throwExc(rule, "Expected matching regex /${r.replace("\n", "\\n")}/")
        match.groups[0]!!.value.also {
            pos += it.length
        }
    }

    protected fun <V> RuleScope.sequence(block: RuleScope.() -> V) = this@GrammarParser.sequence(block).resolve()
    protected fun <V> sequence(block: RuleScope.() -> V) = DelegateRunner {
        try {
            block()
        } catch (e: NoMatchException) {
            if (e.rule == rule) {
                throw e
            }
            throwExc(rule, "Error parsing sequence", e)
        }
    }

    /*
     * Should only be used inside of first() args
     */
    protected fun <V> firstBlock(block: RuleScope.() -> V): () -> () -> V {
        val firstBlock by sequence(block)
        return { firstBlock }
    }
    protected fun <V> RuleScope.first(vararg blocks: () -> () -> V) = this@GrammarParser.first(*blocks).resolve()
    protected fun <V> first(vararg blocks: () -> () -> V) = DelegateRunner {
        val errors = mutableListOf<NoMatchException>()
        for (block in blocks) {
            try {
                save()
                return@DelegateRunner block()().also { drop() }
            } catch (e: NoMatchException) {
                errors.add(e)
                restore()
            }
            // This is no longer available on Kotlin Multiplatform
//            catch (e: StackOverflowError) {
//                throw RuntimeException("Error: Non-memoized recursion detected while parsing rule '$rule'")
//            }
        }
        throwExc(rule, "Expected one of: [${errors.joinToString(transform = NoMatchException::rule)}]", errors.minByOrNull(NoMatchException::depth))
    }

    protected fun <V> RuleScope.optional(block: RuleScope.() -> V) = this@GrammarParser.optional(block).resolve()
    protected fun <V> optional(block: RuleScope.() -> V) = DelegateRunner {
        try {
            save()
            block().also { drop() }
        } catch (e: NoMatchException) {
            restore()
            null
        }
    }

    protected fun <V> RuleScope.zeroOrMore(block: RuleScope.() -> V) = this@GrammarParser.zeroOrMore(block).resolve()
    protected fun <V> zeroOrMore(block: RuleScope.() -> V) = DelegateRunner<List<V>> {
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

    protected fun <V> RuleScope.separated(separator: RuleScope.() -> Unit, required: Boolean = false, block: RuleScope.() -> V) = this@GrammarParser.separated(separator, required, block).resolve()
    protected fun <V> separated(separator: RuleScope.() -> Unit, required: Boolean = false, block: RuleScope.() -> V) = sequence {
        val first = (if (required) block() else optional(block)) ?: return@sequence emptyList<V>()
        val remaining = zeroOrMore {
            separator()
            block()
        }
        listOf(first) + remaining
    }

    protected fun <V> RuleScope.oneOrMore(block: RuleScope.() -> V) = this@GrammarParser.oneOrMore(block).resolve()
    protected fun <V> oneOrMore(block: RuleScope.() -> V) = DelegateRunner<List<V>> {
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

    @Deprecated("Removed in 1.0.6")
    protected fun RuleScope.whitespace(optional: Boolean = false) = this@GrammarParser.whitespace(optional).resolve()
    @Deprecated("Removed in 1.0.6")
    protected fun whitespace(optional: Boolean = true) = regex("\\s" + if (optional) "*" else "+")
    @Deprecated("Removed in 1.0.6")
    protected fun <T: Any> whitespace(optional: Boolean = true, block: () -> T) : T {
        val whitespace by whitespace(optional)
        whitespace()
        val res = block()
        whitespace()
        return res
    }

    @Deprecated("Removed in 1.0.6")
    protected fun RuleScope.space(optional: Boolean = false) = this@GrammarParser.space(optional).resolve()
    @Deprecated("Removed in 1.0.6")
    protected fun space(optional: Boolean = true) = regex("[ \t]" + if (optional) "*" else "+")
    @Deprecated("Removed in 1.0.6")
    protected fun <T: Any> space(optional: Boolean = true, block: () -> T) : T {
        val whitespace by space(optional)
        whitespace()
        val res = block()
        whitespace()
        return res
    }

    fun tryParse(input: String) = try {
        this.input = input
        this.pos = 0
        while (this.errors.isNotEmpty()) {
            this.errors.pop()
        }
        while (this.saved.isNotEmpty()) {
            this.saved.pop()
        }

        root()
    } catch (e: NoMatchException) {
        throw errors.peek()
    }

    protected abstract val root: () -> T
}
