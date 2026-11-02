package maestro.cli.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class EventDispatcherTest {

    private val neverLoggedIn = AnalyticsState(
        uuid = "machine-uuid",
        enabled = true,
        lastUploadedTime = null,
    )

    private val identified = neverLoggedIn.copy(
        cachedToken = "token",
        user_id = "user-id",
        email = "user@example.com",
        orgId = "org-id",
    )

    private val queued = ArrayDeque<Runnable>()
    private val identifiedTokens = mutableListOf<String>()
    private val sent = mutableListOf<Map<String, Any>>()
    private val sentEvents = mutableListOf<PostHogEvent>()

    private var token: () -> String? = { null }
    private var state = neverLoggedIn
    private var enabled = true
    private var identify: (String) -> Unit = { }
    private var send: (Map<String, Any>) -> Unit = { sent += it }

    private val dispatcher = EventDispatcher(
        executor = Executor { queued += it },
        isEnabled = { enabled },
        readToken = { token() },
        identifyIfNeeded = {
            identifiedTokens += it
            identify(it)
        },
        readState = { state },
        send = { event, identity ->
            sentEvents += event
            send(identity)
        },
    )

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun userProperties(state: AnalyticsState) = UserProperties.fromAnalyticsState(state).toMap()

    @Test
    fun `logged in event runs in the background with the token read at track time`() {
        token = { "token" }
        identify = { state = identified }

        val event = UserLoggedOutEvent()
        dispatcher.dispatch(event)
        assertThat(sent).isEmpty()
        token = { null }
        runQueued()

        assertThat(sentEvents).containsExactly(event)
        assertThat(identifiedTokens).containsExactly("token")
        assertThat(sent).containsExactly(
            userProperties(identified) + ("\$groups" to mapOf("company" to "org-id"))
        )
    }

    @Test
    fun `logged in user whose identify changed nothing keeps the profile and has no group`() {
        token = { "token" }

        dispatcher.dispatch(UserLoggedOutEvent())
        runQueued()

        assertThat(sent).containsExactly(userProperties(neverLoggedIn))
    }

    @Test
    fun `event tracked from inside identify stays logged in after the token is deleted`() {
        token = { "token" }
        identify = {
            if (state !== identified) {
                state = identified
                token = { null }
                dispatcher.dispatch(UserLoggedOutEvent(), it)
            }
        }

        dispatcher.dispatch(UserLoggedOutEvent())
        runQueued()

        assertThat(sent).hasSize(2)
        sent.forEach { assertThat(it).doesNotContainKey("\$process_person_profile") }
    }

    @Test
    fun `logged out event skips identify and drops the profile and stale group`() {
        state = identified

        dispatcher.dispatch(UserLoggedOutEvent())
        runQueued()

        assertThat(identifiedTokens).isEmpty()
        assertThat(sent).containsExactly(
            userProperties(identified) + ("\$process_person_profile" to false)
        )
    }

    @Test
    fun `blank or unreadable token counts as logged out`() {
        listOf<() -> String?>({ "" }, { " " }, { error("token file vanished") }).forEach {
            token = it
            dispatcher.dispatch(UserLoggedOutEvent())
        }
        runQueued()

        assertThat(sent).hasSize(3)
        sent.forEach { assertThat(it).containsEntry("\$process_person_profile", false) }
    }

    @Test
    fun `disabled analytics neither identifies nor sends`() {
        token = { "token" }
        enabled = false

        dispatcher.dispatch(UserLoggedOutEvent())
        runQueued()

        assertThat(identifiedTokens).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `errors while sending stay on the background thread`() {
        send = { throw StackOverflowError() }

        dispatcher.dispatch(UserLoggedOutEvent())
        runQueued()
    }
}
