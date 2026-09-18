package maestro.orchestra

/**
 * Which per-step artifacts a run captures, one flag per axis.
 *
 * ## Additive only
 *
 * Every source that can set these is *additive*: it may ask for an artifact, and it
 * may never suppress one that another source asked for. Callers union their sources
 * and pass the result in, so the order sources are combined in does not matter.
 *
 * This is deliberate rather than incidental. Cloud captures step screenshots for
 * every run, and tooling and staff downstream of it assume those exist; a per-run
 * lever that could switch them off would make that guarantee unmaintainable. The
 * same rule holds locally so the two surfaces behave alike. If you find yourself
 * wanting a "capture nothing" switch, the answer is to not ask for the artifact in
 * the first place, not to add a subtractive source.
 *
 * The sources today are the `maestro test` flags (`--capture-step-screenshots`,
 * `--capture-step-hierarchy`, `--capture-all-step-artifacts`), the `--analyze`
 * preset, the `artifacts` block in the workspace `config.yaml`, and Cloud's own
 * `captureFullArtifacts` preset.
 *
 * ## Defaults
 *
 * The two surfaces default differently on purpose, and only for *passing* steps:
 *
 * | | passing steps | failed/warned step | flow end |
 * |---|---|---|---|
 * | CLI screenshots | off (on with `--analyze`) | always | with screenshots |
 * | CLI hierarchy | off | always | with hierarchy |
 * | Cloud screenshots | **on** | always | on |
 * | Cloud hierarchy | off | always | off |
 *
 * Failed and warned steps capture a screenshot and a hierarchy on every surface
 * regardless of this config, so a failure is always diagnosable. These flags govern
 * the pre-command capture for passing steps and the matching flow-end pair.
 *
 * Hierarchy is the expensive axis -- a `viewHierarchy()` round trip costs roughly a
 * second per step -- which is why it is off by default everywhere and why failed
 * steps are the only ones that pay for it unasked.
 *
 * ## Where config.yaml does not reach
 *
 * Two pre-existing invocation shapes carry no workspace config, so the `artifacts`
 * block is silently absent for them and only the flags apply:
 *
 *  - `maestro test flow.yaml` reads a config only when `--config` is passed.
 *  - `maestro cloud flow.yaml` replaces the workspace config with a synthesized
 *    one listing just that flow.
 *
 * Both work as expected for directory runs and whenever `--config` is given.
 */
data class StepArtifactConfig(
    val captureScreenshots: Boolean = false,
    val captureHierarchy: Boolean = false,
)
