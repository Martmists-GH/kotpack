import com.martmists.kotpack.GrammarParser
import kotlin.test.*

class DummyParser(input: String) : GrammarParser<String>(input) {
    override val root by sequence {
        val res = first(
            ::expr,
            ::commaList
        )
        eoi()
        res
    }

    private val expr: () -> String by sequence {
        val t = term()
        val op = whitespace(block=op)
        val t2 = term()
        "$t$op$t2"
    }

    private val parens by sequence {
        char('(')
        val e = whitespace(block=expr)
        char(')')
        "($e)"
    }

    private val term by first(
        ::number,
        ::parens
    )

    private val commaList: () -> String by sequence(memoLeft {
        val l = first(
            ::commaList,
            ::term
        )
        whitespace {
            char(',')
        }
        val r = term()
        "$l,$r"
    })

    private val number by regex("[1-9][0-9]*")

    private val op by regex("[+-]")
}

class GrammarTest {
    @Test
    fun test() {
        val parser = DummyParser("1+2")
        val res = parser.tryParse()
        assertEquals("1+2", res)
    }

    @Test
    fun test2() {
        val parser = DummyParser("1, 2, 3, 4")
        val res = parser.tryParse()
        assertEquals("1,2,3,4", res)
    }

    @Test
    fun test3() {
        val parser = DummyParser("(1 + 2 ) - (3 + 4)")
        val res = parser.tryParse()
        assertEquals("(1+2)-(3+4)", res)
    }

    @Test
    fun test4() {
        val parser = DummyParser("12 + 01")

        assertFails {
            val res = parser.tryParse()
            assertEquals("12+01", res)
        }
    }
}
