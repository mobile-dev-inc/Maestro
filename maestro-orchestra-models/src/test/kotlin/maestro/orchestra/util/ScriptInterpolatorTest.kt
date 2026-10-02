package maestro.orchestra.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ScriptInterpolatorTest {

    private fun interp(input: String) = interpolate(input){
        "<$it>"
    }

    @Test
    fun `Later brace in plain text does not end the block`() {
        assertThat(interp("\${output.count} things {expected}"))
            .isEqualTo("<output.count> things {expected}")
    }

    @Test
    fun `a regex quantifier after the block is left alone`() {
        assertThat(interp("\${output.count} items \\d{2}"))
            .isEqualTo("<output.count> items \\d{2}")
    }

    @Test
    fun `Block inside a JSON payload is bounded correctly`() {
        assertThat(interp("""{"user": "${'$'}{output.name}"}"""))
            .isEqualTo("""{"user": "<output.name>"}""")
    }

    @Test
    fun `Script may contain the text dollar-brace`() {
        assertThat(interp("\${'\\\${A}'}")).isEqualTo("<'\\\${A}'>")
    }

    @Test
    fun `Script may contain a nested interpolation in a template literal`() {
        assertThat(interp("\${`a\${b}c`}")).isEqualTo("<`a\${b}c`>")
    }


    @Test
    fun `Braces before the block are plain text`() {
        assertThat(interp("{literal} \${output.name}")).isEqualTo("{literal} <output.name>")
    }

    @Test
    fun `Brace inside a string literal is not counted`() {
        assertThat(interp("\${'}'}")).isEqualTo("<'}'>")
        assertThat(interp("\${'{'}")).isEqualTo("<'{'>")
    }

    @Test
    fun `Brace inside a regex literal is not counted`() {
        assertThat(interp("\${'a'.replace(/[{]/g, '')}")).isEqualTo("<'a'.replace(/[{]/g, '')>")
    }

    @Test
    fun `Quoted brace no longer depends on being last in the string`() {
        assertThat(interp("\${'}'} and one more }")).isEqualTo("<'}'> and one more }")
    }

    @Test
    fun `Every block in a string is evaluated`() {
        assertThat(interp("\${A} and \${B}")).isEqualTo("<A> and <B>")
    }

    @Test
    fun `Escaped block stays literal`() {
        assertThat(interp("\\\${LITERAL}")).isEqualTo("\${LITERAL}")
    }

    @Test
    fun `Escaped backslash still escapes the block`() {
        assertThat(interp("\\\\\${A}")).isEqualTo("\\\${A}")
    }

    @Test
    fun `Empty block evaluates to nothing`() {
        assertThat(interp("a\${}b")).isEqualTo("ab")
        assertThat(interp("a\${  }b")).isEqualTo("ab")
    }

    @Test
    fun `String without a block is returned unchanged`() {
        assertThat(interp("no scripts { } here")).isEqualTo("no scripts { } here")
    }

    @Test
    fun `Object literal inside the block is bounded correctly`() {
        assertThat(interp("\${ {a: 1}.a } tail")).isEqualTo("< {a: 1}.a > tail")
    }

    @Test
    fun `Division is not mistaken for a regex literal`() {
        assertThat(interp("\${ 10 / 2 } {x}")).isEqualTo("< 10 / 2 > {x}")
    }

    @Test
    fun `Slashes inside a string are not a comment`() {
        assertThat(interp("\${ '//' + '}' } {x}")).isEqualTo("< '//' + '}' > {x}")
    }

    @Test
    fun `Brace inside a block comment is not counted`() {
        assertThat(interp("\${ 1 /* } */ + 1 }")).isEqualTo("< 1 /* } */ + 1 >")
    }

    @Test
    fun `Unterminated block is emitted verbatim`() {
        assertThat(interp("cost: \${foo")).isEqualTo("cost: \${foo")
    }

    @Test
    fun `Unterminated escaped block is emitted verbatim`() {
        assertThat(interp("cost: \\\${foo")).isEqualTo("cost: \\\${foo")
    }

    @Test
    fun `Brace on a later line does not end the block`() {
        assertThat(interp("line1 \${A}\nline2 }")).isEqualTo("line1 <A>\nline2 }")
    }

    @Test
    fun `Brace inside a line comment is not counted`() {
        assertThat(interp("\${ 1 + // }\n 1 }")).isEqualTo("< 1 + // }\n 1 >")
    }

    @Test
    fun `Blocks on separate lines are evaluated independently`() {
        assertThat(interp("\${ 'a' }\n\${ 'b' }")).isEqualTo("< 'a' >\n< 'b' >")
    }

    @Test
    fun `A keyword before the slash starts a regex literal`() {
        assertThat(interp("\${ (function(){ return /}/.test('a') })() }"))
            .isEqualTo("< (function(){ return /}/.test('a') })() >")
    }

    @Test
    fun `A slash after a closing parenthesis is a division`() {
        assertThat(interp("\${ f(x) / 2 }")).isEqualTo("< f(x) / 2 >")
    }

    @Test
    fun `A closing brace inside a regex character class is not counted`() {
        assertThat(interp("\${'a'.replace(/[}]/g, '')}")).isEqualTo("<'a'.replace(/[}]/g, '')>")
    }

    @Test
    fun `A slash inside a regex character class does not end the regex`() {
        assertThat(interp("\${'a/b'.split(/[/]/)}")).isEqualTo("<'a/b'.split(/[/]/)>")
    }

    @Test
    fun `A quote inside a regex literal does not open a string`() {
        assertThat(interp("\${'x'.replace(/'/g, '')}")).isEqualTo("<'x'.replace(/'/g, '')>")
    }

    @Test
    fun `An escaped quote does not end the string literal`() {
        assertThat(interp("\${ 'don\\'t }' }")).isEqualTo("< 'don\\'t }' >")
    }

    @Test
    fun `An escaped backtick does not end the template literal`() {
        assertThat(interp("\${`a\\`}b`}")).isEqualTo("<`a\\`}b`>")
    }

    @Test
    fun `A quote inside a template literal is plain text`() {
        assertThat(interp("\${`it's }`}")).isEqualTo("<`it's }`>")
    }

    @Test
    fun `Templates nest to any depth`() {
        assertThat(interp("\${`a\${`b\${c}d`}e`}")).isEqualTo("<`a\${`b\${c}d`}e`>")
    }

    @Test
    fun `A block with an unterminated string is emitted verbatim`() {
        assertThat(interp("\${ 'oops }")).isEqualTo("\${ 'oops }")
    }

    @Test
    fun `A block with an unterminated template is emitted verbatim`() {
        assertThat(interp("\${ `oops }")).isEqualTo("\${ `oops }")
    }

    @Test
    fun `An unterminated regex falls back to a plain slash and the block still closes`() {
        assertThat(interp("\${ /oops }")).isEqualTo("< /oops >")
    }

    @Test
    fun `The evaluated result is not scanned again`() {
        val evaluated = interpolate("x \${A} y") { "\${INJECTED} }" }

        assertThat(evaluated).isEqualTo("x \${INJECTED} } y")
    }

    @Test
    fun `An escaped block is never evaluated`() {
        val scripts = mutableListOf<String>()

        val evaluated = interpolate("\\\${A} and \${B}") {
            scripts += it
            "<$it>"
        }

        assertThat(scripts).containsExactly("B")
        assertThat(evaluated).isEqualTo("\${A} and <B>")
    }

    @Test
    fun `A dollar that opens no block is plain text`() {
        assertThat(interp("price: \$5")).isEqualTo("price: \$5")
        assertThat(interp("costs 100\$")).isEqualTo("costs 100\$")
    }

    @Test
    fun `A block opened at the very end of the string is emitted verbatim`() {
        assertThat(interp("abc\${")).isEqualTo("abc\${")
    }

    @Test
    fun `A trailing backslash is plain text`() {
        assertThat(interp("trailing backslash \\")).isEqualTo("trailing backslash \\")
    }

    @Test
    fun `Adjacent blocks are evaluated separately`() {
        assertThat(interp("\${A}\${B}")).isEqualTo("<A><B>")
    }

    @Test
    fun `An escaped block next to a real one does not affect it`() {
        assertThat(interp("\\\${A}\${B}")).isEqualTo("\${A}<B>")
        assertThat(interp("\${A}\\\${B}")).isEqualTo("<A>\${B}")
    }

    @Test
    fun `Non-ASCII text and identifiers are preserved`() {
        assertThat(interp("Grüße, \${straße}!")).isEqualTo("Grüße, <straße>!")
        assertThat(interp("\${名前} さん")).isEqualTo("<名前> さん")
    }

    @Test
    fun `Characters outside the basic plane survive the scan`() {
        assertThat(interp("👋 \${'🎉'} {x}")).isEqualTo("👋 <'🎉'> {x}")
    }

}
