package maestro.cli.runner

import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.OrchestraListener
import org.slf4j.LoggerFactory

/** Console-output listener for `maestro test`: one log line per command lifecycle event. */
class CliConsoleListener(private val shardPrefix: String = "") : OrchestraListener {

    private val logger = LoggerFactory.getLogger(CliConsoleListener::class.java)

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
        logger.info("${shardPrefix}${cmd.description()} RUNNING")
    }

    override fun onCommandFinished(
        cmd: MaestroCommand,
        outcome: CommandOutcome,
        startedAt: Long,
        finishedAt: Long,
    ) {
        val word = when (outcome) {
            is CommandOutcome.Completed -> "COMPLETED"
            is CommandOutcome.Failed -> "FAILED"
            is CommandOutcome.Skipped -> "SKIPPED"
            is CommandOutcome.Warned -> "WARNED"
        }
        logger.info("${shardPrefix}${cmd.description()} $word")
    }

    override fun onCommandReset(cmd: MaestroCommand) {
        logger.info("${shardPrefix}${cmd.description()} PENDING")
    }
}
