# The `simctl recordVideo` command requires a SIGINT to be sent to stop the recording.
# Before the SIGINT is sent, the video file is not playable.
# Kotlin / JVM has no API to sent signals to subprocesses.
# To work around that one could try to use `kill -SIGINT $pid`.
# Kotlin / JVM on language level < 9 has no API to get the PID of a subprocess.
# There just isn't a good way to make Kotlin record a video using xctest simctl.
# That's where this script comes in. It send the SIGINT to simctl as soon as its
# STDIN pipe is closed.

# Also not that the backend currently does not support hvec. That is why the
# codec is set to h264.

echo "Start recording"
xcrun simctl io "$DEVICE_ID" recordVideo --force --codec h264 "$RECORDING_PATH" &
simctlpid=$!

echo "Simctl pid $simctlpid"

# Wait for STDIN to close
cat

echo "Received request to stop recording"

kill -SIGINT "$simctlpid"
wait $simctlpid
