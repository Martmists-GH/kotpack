# Kotpack

Kotpack is a Packrat parser for Kotlin with support for left-recursion.

## Usage

### Gradle

```kotlin
repositories {
    maven("https://maven.martmists.com/releases")
    maven("https://maven.martmists.com/snapshots")  // For getting builds of a specific commit 
}

dependencies {
    implementation("com.martmists:kotpack:1.0.0")
}
```

### Example

```kotlin
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
        // regex, string and char allow for callbacks to transform the result if they match
        it.toDouble()
    }
}

fun main() {
    val parser = MathParser("1 + 2 * 3")
    val result = parser.tryParse()
    println(result)  // 9.0, as we never made it follow PEMDAS and instead it parses left-to-right
}
```

## All methods

```kotlin
// Match a character
// rule := '-'
char('-')
char('-') {
    // Transform the char if necessary
}

// Match a string
// rule := 'int'
string("int")
string("int") {
    // Transform the string if necessary
}

// Match a regex
// rule := r'[0-9]+'
regex("[0-9]+")
regex("[0-9]+") {
    // Transform the string if necessary
}

// permit whitespace around rules
// rule := r'[ \t]*' rule1 rule2 r'[ \t]*'
// > if whitespace is required, i.e. r'[ \t]+', then use optional=false
whitespace(optional=true) {
    rule1()
    rule2()
}

// Elements must appear in order
// rule := rule1 rule2 rule3
sequence {
    rule1()
    rule2()
    rule3()
}

// Optional rules
// rule := rule1?
optional {
    rule1()
}

// One or more
// rule := rule1 ('+' rule1)+
sequence {
    rule1()
    oneOrMore {
        char('+')
        rule1()
    }
}

// Zero or more
// rule := rule1 ('+' rule1)*
sequence { 
    rule1()
    zeroOrMore {
        char('+')
        rule1()
    }
}

// The first matching rule is returned
// rule := rule1 | rule2
first(
    ::rule1,
    ::rule2,
)

// To speed up complex parsing, you can use memoization
// rule := (rule1 | rule2 | rule3) rule4
sequence(memo {
    first(
        ::rule1,
        ::rule2,
        ::rule3,
    )
    rule4()
})

// For left-recursion you need to use memoLeft
// Not compatible with memo
// rule := (rule | term) '+' term
sequence(memoLeft {
    first(
        ::rule,
        ::term,
    )
    char('+')
    expr()
})
```

## License

This project is licensed under the BSD-4-Clause license.
