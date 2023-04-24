package maestro

import maestro.mockserver.MockEvent
import maestro.mockserver.MockInteractor
import maestro.utils.StringUtils.toRegexSafe
import java.util.UUID

data class OutgoingRequestRules(
    val path: String? = null,
    val headersPresent: List<String> = emptyList(),
    val headersAndValues: Map<String, String> = emptyMap(),
    val httpMethodIs: String? = null,
    val requestBodyContains: String? = null,
)

object AssertOutgoingRequestService {

    private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

    fun match(events: List<MockEvent>, rules: OutgoingRequestRules): List<MockEvent> {
        val eventsFilteredByUrl = rules.path?.let { path ->
            events.filter { e -> e.path == path || e.path.matches(path.toRegexSafe(REGEX_OPTIONS)) }
        } ?: events

        val eventsFilteredByHttpMethod = rules.httpMethodIs?.let { httpMethod ->
            eventsFilteredByUrl.filter { e -> e.method == httpMethod }
        } ?: eventsFilteredByUrl

        val eventsFilteredByHeader = rules.headersPresent.fold(eventsFilteredByHttpMethod) { eventsList, header ->
            eventsList.filter { e -> e.headers?.containsKey(header.lowercase()) == true }
        }

        val eventsFilteredByHeadersAndValues = rules.headersAndValues.entries.fold(eventsFilteredByHeader) { eventsList, (header, value) ->
            eventsList.filter { e -> e.headers?.get(header.lowercase()) == value }
        }

        val eventsMatching = rules.requestBodyContains?.let { requestBody ->
            eventsFilteredByHeadersAndValues.filter { e -> e.bodyAsString?.contains(requestBody) == true }
        } ?: eventsFilteredByHeadersAndValues

        return eventsMatching
    }

    /*
    There might be a delay between the mock event being stored in our Maestro Cloud and the client trying to retrieve it.
    That's why we have retries
     */
    fun getMockEvents(sessionId: UUID): List<MockEvent> {
        var attempts = 1
        val maxRetries = 3
        var events: List<MockEvent> = emptyList()

        while (events.isEmpty() && attempts <= maxRetries) {
            events = MockInteractor().getMockEvents().filter { it.sessionId == sessionId }
            if (events.isEmpty()) Thread.sleep(3000L) else attempts = maxRetries + 1
            attempts += 1
        }

        return events
    }
}