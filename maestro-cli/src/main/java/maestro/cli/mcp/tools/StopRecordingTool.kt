package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*

object StopRecordingTool {
    fun create(recordingManager: RecordingManager = RecordingManager.getDefault()): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "stop_recording",
                description = "Stop an active iOS Simulator screen recording and return the video file path.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device being recorded")
                        }
                        putJsonObject("recording_id") {
                            put("type", "string")
                            put("description", "The recording ID returned by start_recording")
                        }
                    },
                    required = listOf("device_id", "recording_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val recordingId = request.arguments["recording_id"]?.jsonPrimitive?.content

                if (deviceId == null || recordingId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and recording_id are required")),
                        isError = true
                    )
                }

                val result = recordingManager.stopRecording(deviceId, recordingId)

                val json = buildJsonObject {
                    put("success", true)
                    put("video_path", result.videoPath)
                }.toString()

                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to stop recording: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
