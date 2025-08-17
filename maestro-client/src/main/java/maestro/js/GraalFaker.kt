package maestro.js

import net.datafaker.Faker
import org.graalvm.polyglot.HostAccess.Export

class GraalFaker() {
    val faker = Faker()

    @Export
    fun generate(
        expression: String
    ): String {
        var thisExpression = expression
        if(!expression.contains("#{")){ // Allow users to provide full faker expressions, but allow plain X.y
            thisExpression = "#{${thisExpression}}"
        }
        return faker.expression(thisExpression)
    }

    @JvmOverloads
    @Export
    fun number(length : Int = 8): String {
        return faker.number().randomNumber(length).toString()
    }

    @JvmOverloads
    @Export
    fun text(length: Int = 8): String {
        return faker.text().text(length)
    }

    @Export
    fun email(): String {
        return faker.internet().emailAddress()
    }

    @Export
    fun personName(): String {
        return faker.name().name()
    }

    @Export
    fun city(): String {
        return faker.address().cityName()
    }

    @Export
    fun country(): String {
        return faker.address().country()
    }

    @Export
    fun color(): String {
        return faker.color().name()
    }
}