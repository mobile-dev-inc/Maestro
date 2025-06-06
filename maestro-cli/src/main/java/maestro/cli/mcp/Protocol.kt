@file:OptIn(ExperimentalSerializationApi::class)

package maestro.cli.mcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
@JvmInline
value class RequestId(val value: Long)

@Serializable
data class Implementation(
    val name: String,
    val version: String,
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability = ToolsCapability()
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean = true
)

@Serializable
@JsonClassDiscriminator("method")
sealed class Request {
    val jsonrpc: String = "2.0"
    abstract val id: RequestId?
}

@Serializable
@SerialName("initialize")
data class InitializeRequest(
    override val id: RequestId,
    val params: InitializeRequestParams,
) : Request()

@Serializable
data class InitializeRequestParams(
    val protocolVersion: String,
)

@Serializable
@SerialName("tools/list")
data class ToolsListRequest(
    override val id: RequestId,
) : Request()

@Serializable
@SerialName("tools/call")
data class ToolCallRequest(
    override val id: RequestId,
    val params: ToolCallParams,
) : Request()

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: Map<String, JsonObject> = emptyMap(),
    val serverInfo: ServerInfo,
    val instructions: String? = null
)

@Serializable
data class InitializeResponse(
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: RequestId,
    val result: InitializeResult
)

@Serializable
data class ToolsListResponse(
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: RequestId,
    val result: ToolsListResult
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallResponse(
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: RequestId,
    val result: ToolCallResult
)

@Serializable
data class ToolCallResult(
    val content: List<ContentItem>,
    val isError: Boolean = false
)

@Serializable
@JsonClassDiscriminator("type")
sealed class ContentItem

@Serializable
@SerialName("text")
data class TextContent(
    val text: String
) : ContentItem()

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String,
    val mimeType: String
) : ContentItem()

@Serializable
@SerialName("audio")
data class AudioContent(
    val data: String,
    val mimeType: String
) : ContentItem()

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolInputSchema,
)

@Serializable
data class ToolInputSchema(
    val type: String,
    val properties: Map<String, ToolInputSchema> = emptyMap(),
    val description: String? = null,
    val required: List<String> = emptyList(),
) 