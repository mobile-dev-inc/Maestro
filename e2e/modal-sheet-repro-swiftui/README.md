# SwiftUI nested sheet regression fixture

Minimal reproduction for #1924 and #1503: a full-screen cover presents a sheet
with accessibility identifiers. The `nested_modals` workspace checks direct and
nested sheets, passive sheet content, covered controls, dismissal, alerts and text
input with the default driver configuration.

Build from the repository root:

```sh
e2e/modal-sheet-repro-swiftui/build.sh
```

`install_apps ios` also builds the fixture before installing apps. The generated
`e2e/apps/ModalSheetRepro.app` supports arm64 and x86_64 simulators and stays ignored.

With the fixture installed, a booted simulator and the CLI built from this checkout:

```sh
maestro test e2e/workspaces/nested_modals
```

The text-input flow submits with Enter rather than using `hideKeyboard`, whose
swipe can dismiss the sheet.
