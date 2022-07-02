package conductor

sealed class ConductorException(message: String) : RuntimeException(message) {

    class UnableToLaunchApp(message: String) : ConductorException(message)

    class ElementNotFound(message: String) : ConductorException(message)

}