package maestro.device.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import maestro.device.DeviceSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Custom Jackson serializer for DeviceSpec that omits fields whose values
 * equal the declared default on the primary constructor.
 *
 * Always emitted: the `platform` discriminator and any constructor parameter
 * that has no default (model, os).
 *
 * Omitted: any constructor parameter whose runtime value equals the default
 * value extracted by invoking the primary constructor with placeholder values
 * for required params. Computed get() properties defined outside the primary
 * constructor are never iterated and therefore never emitted.
 */
class DeviceSpecSparseSerializer : StdSerializer<DeviceSpec>(DeviceSpec::class.java) {

    private data class DefaultsInfo(
        val defaults: Map<String, Any?>,
        val paramOrder: List<String>,
    )

    private val cache = ConcurrentHashMap<KClass<*>, DefaultsInfo>()

    override fun serialize(value: DeviceSpec, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField("platform", value.platform.name)

        val info = cache.getOrPut(value::class) { buildDefaultsInfo(value::class) }
        val propsByName = value::class.memberProperties.associateBy { it.name }

        for (paramName in info.paramOrder) {
            if (paramName == "platform") continue
            val prop = propsByName[paramName] ?: continue
            prop.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val runtimeValue = (prop as KProperty1<Any, Any?>).get(value)

            val hasDefault = info.defaults.containsKey(paramName)
            if (hasDefault && runtimeValue == info.defaults[paramName]) continue

            provider.defaultSerializeField(paramName, runtimeValue, gen)
        }

        gen.writeEndObject()
    }

    // DeviceSpec is annotated with @JsonTypeInfo(As.EXISTING_PROPERTY, property = "platform").
    // Since our serialize() already emits the `platform` discriminator field, we delegate
    // serializeWithType straight to serialize() rather than letting Jackson try to inject
    // a type id (which StdSerializer.serializeWithType would otherwise refuse to do).
    override fun serializeWithType(
        value: DeviceSpec,
        gen: JsonGenerator,
        provider: SerializerProvider,
        typeSer: TypeSerializer,
    ) {
        serialize(value, gen, provider)
    }

    private fun buildDefaultsInfo(klass: KClass<*>): DefaultsInfo {
        val ctor = klass.primaryConstructor
            ?: error("DeviceSpec subtype ${klass.simpleName} must have a primary constructor")
        ctor.isAccessible = true

        val paramOrder = ctor.parameters.map { it.name!! }

        val requiredArgs: Map<KParameter, Any?> = ctor.parameters
            .filter { !it.isOptional }
            .associateWith { param ->
                when (param.type.classifier) {
                    String::class -> "placeholder"
                    else -> null
                }
            }

        val defaultInstance = try {
            ctor.callBy(requiredArgs)
        } catch (e: Exception) {
            error("Failed to build default instance of ${klass.simpleName}: ${e.message}")
        }

        val propsByName = klass.memberProperties.associateBy { it.name }
        val defaults: Map<String, Any?> = ctor.parameters
            .filter { it.isOptional }
            .associate { param ->
                val prop = propsByName[param.name!!]
                    ?: error("No member property for parameter ${param.name} on ${klass.simpleName}")
                prop.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val defaultValue = (prop as KProperty1<Any, Any?>).get(defaultInstance)
                param.name!! to defaultValue
            }

        return DefaultsInfo(defaults = defaults, paramOrder = paramOrder)
    }
}
