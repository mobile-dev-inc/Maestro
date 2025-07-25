package network

import maestro.utils.network.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ErrorsTest {

    @Test
    fun `InputFieldNotFound should have correct message`() {
        assertThrows<InputFieldNotFound>("Unable to find focused input field") {
            throw InputFieldNotFound()
        }
    }

    @Test
    fun `UnknownFailure should have correct message`() {
        val errorMessage = "An unknown error occurred"

        assertThrows<UnknownFailure>(errorMessage) {
            throw UnknownFailure(errorMessage)
        }
    }

    @Test
    fun `XCUITestServerResult Success should contain data`() {
        val data = "Test Data"
        val result = XCUITestServerResult.Success(data)

        assertEquals(data, result.data)
    }

    @Test
    fun `XCUITestServerResult Failure should contain error`() {
        val error = XCUITestServerError.UnknownFailure("Error")
        val result = XCUITestServerResult.Failure(error)

        assertEquals(error, result.errors)
    }

    @Test
    fun `XCUITestServerError UnknownFailure should have correct message`() {
        val errorMessage = "Unknown error"

        assertThrows<XCUITestServerError.UnknownFailure>(errorMessage) {
            throw XCUITestServerError.UnknownFailure(errorMessage)
        }
    }

    @Test
    fun `XCUITestServerError NetworkError should have correct message`() {
        val errorMessage = "Network error"

        assertThrows<XCUITestServerError.NetworkError>(errorMessage) {
            throw XCUITestServerError.NetworkError(errorMessage)
        }
    }

    @Test
    fun `XCUITestServerError AppCrash should have correct message`() {
        val errorMessage = "App crashed"

        assertThrows<XCUITestServerError.AppCrash>(errorMessage) {
            throw XCUITestServerError.AppCrash(errorMessage)
        }
    }

    @Test
    fun `XCUITestServerError BadRequest should have correct messages`() {
        val errorMessage = "Bad request"
        val clientMessage = "Client error"

        assertThrows<XCUITestServerError.BadRequest>(errorMessage) {
            throw XCUITestServerError.BadRequest(errorMessage, clientMessage)
        }
    }
}