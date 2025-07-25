package maestro.orchestra.yaml.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class YamlFile(val name: String)
