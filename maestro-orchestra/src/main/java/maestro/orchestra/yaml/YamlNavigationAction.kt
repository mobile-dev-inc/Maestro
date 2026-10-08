package maestro.orchestra.yaml

/**
 * The words `action` accepts. `action` is a legacy spelling of commands that all exist under their own
 * names — `action: back` is `- back` — and it is a plain `String` so that it keeps parsing as one, but
 * the set of words it takes is closed. Holding them here rather than as literals in a `when` is what
 * lets the schema advertise them; [yamlValue] is the word, the constant name is not on any wire.
 */
enum class YamlNavigationAction(val yamlValue: String) {
    Back("back"),
    HideKeyboard("hideKeyboard"),
    Scroll("scroll"),
    ClearKeychain("clearKeychain"),
    PasteText("pasteText"),
}
