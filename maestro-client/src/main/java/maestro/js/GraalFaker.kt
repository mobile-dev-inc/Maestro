package maestro.js

import net.datafaker.Faker
import net.datafaker.providers.base.AbstractProvider
import org.graalvm.polyglot.HostAccess

class GraalFaker : GraalHostAccessible {
    private val publicClasses = mutableSetOf<Class<*>>()

    fun getFaker(): Faker = Faker()

    override fun configureHostAccess(builder: HostAccess.Builder) {
        builder.apply { allowAllPublicOf(Faker::class.java) }
    }

    private fun HostAccess.Builder.allowAllPublicOf(clazz: Class<*>) {
        if (clazz in publicClasses) return
        publicClasses.add(clazz)
        clazz.methods.filter {
            it.declaringClass != Object::class.java &&
            it.declaringClass != AbstractProvider::class.java &&
            java.lang.reflect.Modifier.isPublic(it.modifiers)
        }.forEach { method ->
            allowAccess(method)
            if (AbstractProvider::class.java.isAssignableFrom(method.returnType) && !publicClasses.contains(method.returnType)) {
                allowAllPublicOf(method.returnType)
            }
        }
    }
}