package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*

object StartRecordingTool {
    fun create(recordingManager: RecordingManager = RecordingManager.getDefault()): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "start_recording",
                description = "Start recording the simulator screen. Uses xcrun simctl io recordVideo internally.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to record")
                        }
                        putJsonObject("output_path") {
                            put("type", "string")
                            put("description", "File path for the recording output (optional, a temp path is used if omitted)")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val outputPath = request.arguments["output_path"]?.jsonPrimitive?.content

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }

                val state = recordingManager.startRecording(deviceId, outputPath)

                val result = buildJsonObject {
                    put("success", true)
                    put("recording_id", state.recordingId)
                    put("output_path", state.outputPath ?: state.screenRecording.file.absolutePath)
                }.toString()

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to start recording: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
