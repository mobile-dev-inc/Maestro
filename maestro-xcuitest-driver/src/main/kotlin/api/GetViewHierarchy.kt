package api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hierarchy.Error
import hierarchy.XCUIElement
import xcuitest.XCTestDriverClient

object GetViewHierarchy {

    private val mapper by lazy { jacksonObjectMapper() }

    private const val VIEW_HIERARCHY_SNAPSHOT_ERROR_CODE = "illegal-argument-snapshot-failure"

    fun invoke(appId: String): Result<XCUIElement, Throwable> {
        return try {
            XCTestDriverClient.subTree(appId).use {
                if (it.isSuccessful) {
                    val xcUiElement = it.body?.let { response ->
                        mapper.readValue(String(response.bytes()), XCUIElement::class.java)
                    } ?: throw IllegalStateException("View Hierarchy not available, response body is null")
                    Ok(xcUiElement)
                } else {
                    val err = it.body?.let { response ->
                        val errorResponse = String(response.bytes()).trim()
                        val error = mapper.readValue(errorResponse, Error::class.java)
                        when (error.errorCode) {
                            VIEW_HIERARCHY_SNAPSHOT_ERROR_CODE -> Err(IllegalArgumentSnapshotFailure())
                            else -> Err(UnknownFailure(errorResponse))
                        }
                    } ?: Err(UnknownFailure("Error body for view hierarchy request not available"))
                    err
                }
            }
        } catch (exception: Throwable) {
            Err(exception)
        }
    }

    class IllegalArgumentSnapshotFailure: Throwable()
    class UnknownFailure(val errorResponse: String): Throwable()
}