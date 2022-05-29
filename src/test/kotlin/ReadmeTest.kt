import com.martmists.kotpack.GrammarParser
import kotlin.test.*

/**
 * Grammar parser for the following grammar:
 * root         := expr;
 * expr         := op | parentheses | num;
 * op           := expr whitespace r"[/+*-]" whitespace expr;
 * parentheses  := '(' whitespace expr whitespace ')';
 * num          := r"[0-9]+(\.[0-9]+)?";
 * whitespace   := r"[ \t]*";
 */
class MathParser(input: String) : GrammarParser<Double>(input) {  // root rule returns a Double
    override val root by sequence {
        val res = expression()
        eoi()  // we must hit end of input
        res
    }

    private val expression: () -> Double by first(  // we have to specify the type due to type calculation recursion
        ::op,
        ::parentheses,
        ::num
    )

    private val op by sequence(memoLeft {  // we use memoLeft to avoid crashing from left-recursion
        val left = expression()
        val op = whitespace(optional=true) {  // remove whitespace before *and* after everything in this block
            regex("[+\\-/*]")
        }
        // Due to how memoLeft is implemented, we can't use expression() here as it would match the right before it matches the left
        // In case of `1 + 2 * 3`, it would get parsed as `1 + (2 * 3)` instead of `(1 + 2) * 3` like the grammar suggests
        val right = first(
            ::parentheses,
            ::num
        )
        when (op) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> left / right
            else -> throw IllegalStateException("Unknown operator: $op")
        }
    })

    private val parentheses by sequence {
        char('(')  // match a single character
        val res = whitespace {  // defaults to optional=true when invoked as block, but invoking as whitespace() makes it required by default!
            expression()
        }
        char(')')
        res
    }

    private val num by regex("[0-9]+(\\.[0-9]+)?") {
        // regex. string and char allow for callbacks to transform the result if they match
        it.toDouble()
    }
}

class ReadmeTest {
    @Test
    fun test() {
        val parser = MathParser("1 + 2 * 3")
        val result = parser.tryParse()

        // Note that we didn't use anything to follow PEMDAS, so it just evaluates LTR
        assertEquals(9.0, result)
    }
}
