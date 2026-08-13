package maestro.orchestra.devicecore

import maestro.MaestroException
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.debug.StepTraceEmitter

/**
 * The thin executor for the device-core `maestro test` path: walks a parsed flow's commands in
 * order and dispatches the four verbs the four-command vertical supports to [driver]. Anything
 * else — an unsupported command, or an unsupported modifier on a supported command — throws
 * [MaestroException.NotImplemented] naming the command/modifier, never a silent no-op.
 *
 * When [traceEmitter] is given, every step is recorded: [Verdict.PASS] with the driver's returned
 * [ChosenElement] on success, [Verdict.FAIL] on a thrown [MaestroException] (an assert/tap failure,
 * or an unimplemented command/modifier), [Verdict.ERROR] on anything else. The trace is written
 * before the exception is rethrown, so a failing step is always the last line and the run still
 * fails.
 */
class DeviceCoreFlowRunner(
    private val driver: DeviceCoreDriver,
    private val traceEmitter: StepTraceEmitter? = null,
) {

    fun run(commands: List<MaestroCommand>, config: MaestroConfig?) {
        commands.forEachIndexed { index, maestroCommand ->
            val commandType = maestroCommand.asCommand()?.let { it::class.simpleName } ?: "null"
            try {
                val chosen = dispatch(maestroCommand)
                traceEmitter?.emit(
                    stepIndex = index,
                    commandType = commandType,
                    selectorText = maestroCommand.elementSelector()?.textRegex,
                    selectorId = maestroCommand.elementSelector()?.idRegex,
                    verdict = Verdict.PASS,
                    chosen = chosen,
                )
            } catch (e: MaestroException) {
                traceEmitter?.emit(
                    stepIndex = index,
                    commandType = commandType,
                    selectorText = maestroCommand.elementSelector()?.textRegex,
                    selectorId = maestroCommand.elementSelector()?.idRegex,
                    verdict = Verdict.FAIL,
                    chosen = null,
                )
                throw e
            } catch (t: Throwable) {
                traceEmitter?.emit(
                    stepIndex = index,
                    commandType = commandType,
                    selectorText = maestroCommand.elementSelector()?.textRegex,
                    selectorId = maestroCommand.elementSelector()?.idRegex,
                    verdict = Verdict.ERROR,
                    chosen = null,
                )
                throw t
            }
        }
    }

    private fun dispatch(maestroCommand: MaestroCommand): ChosenElement? {
        return when (val command = maestroCommand.asCommand()) {
            is LaunchAppCommand -> runLaunchApp(command)
            is TapOnElementCommand -> runTapOnElement(command)
            is AssertConditionCommand -> runAssertCondition(command)
            else -> throw MaestroException.NotImplemented(
                "device-core does not implement ${command?.let { it::class.simpleName } ?: "null command"}"
            )
        }
    }

    private fun runLaunchApp(command: LaunchAppCommand): ChosenElement? {
        if (command.clearState == true) {
            throw MaestroException.NotImplemented("launchApp modifier clearState")
        }
        if (command.clearKeychain == true) {
            throw MaestroException.NotImplemented("launchApp modifier clearKeychain")
        }
        if (command.permissions != null) {
            throw MaestroException.NotImplemented("launchApp modifier permissions")
        }
        if (!command.launchArguments.isNullOrEmpty()) {
            throw MaestroException.NotImplemented("launchApp modifier launchArguments")
        }
        if (command.stopApp == false) {
            throw MaestroException.NotImplemented("launchApp modifier stopApp")
        }
        driver.launchApp(command.appId)
        return null
    }

    private fun runTapOnElement(command: TapOnElementCommand): ChosenElement? {
        if (command.longPress == true) {
            throw MaestroException.NotImplemented("tapOnElement modifier longPress")
        }
        if (command.repeat != null) {
            throw MaestroException.NotImplemented("tapOnElement modifier repeat")
        }
        if (command.relativePoint != null) {
            throw MaestroException.NotImplemented("tapOnElement modifier relativePoint")
        }
        return driver.tap(command.selector)
    }

    private fun runAssertCondition(command: AssertConditionCommand): ChosenElement? {
        val condition = command.condition
        val visible = condition.visible
        val notVisible = condition.notVisible
        return when {
            visible != null -> driver.assertVisibility(visible, AssertMode.VISIBLE)
            notVisible != null -> driver.assertVisibility(notVisible, AssertMode.NOT_VISIBLE)
            else -> throw MaestroException.NotImplemented("assert condition: ${condition.description()}")
        }
    }
}
