# CLI Validation Gap Analysis

Comparison of validation logic between the copilot backend (`RunMaestroRoute` → `AppStorage` + `WorkspaceEvaluator`) and the CLI (`AppValidator` + `WorkspaceValidator` in `maestro-orchestra`).

Goal: shift validation to CLI so errors are caught before upload. Once all users are on a CLI version with these validations, the backend validation can be removed.

## Workspace Validation

| Check | Backend (`WorkspaceEvaluator`) | CLI (`WorkspaceValidator`) | Status |
|-------|-------------------------------|---------------------------|--------|
| Empty workspace | `EmptyWorkspace` | `EmptyWorkspace` | Covered |
| No flows matching appId | `NoFlowsMatchingAppBundleId` | `NoFlowsMatchingAppId` | Covered |
| Name conflicts | `NameConflict` | `NameConflict` | Covered |
| Syntax errors | `SyntaxError` | `SyntaxError` | Covered |
| Invalid flow file | `InvalidFlowFile` | `InvalidFlowFile` | Covered |
| Invalid zip | `InvalidWorkspaceFile` | `InvalidWorkspaceFile` | Covered |
| Missing launchApp | Throws `SyntaxError` | `MissingLaunchApp` (dedicated error) | Covered |
| Deprecated commands | `DeprecatedCommandUsage` | Not checked | **Gap** |
| `excludeNotSupportedCommands` | Applied during flow reading | Not applied | **Gap** |

## App Validation

| Check | Backend (`AppStorage`) | CLI (`AppValidator` + `AppMetadataAnalyzer`) | Status |
|-------|------------------------|----------------------------------------------|--------|
| Infer platform (iOS/Android/Web) | `inferPlatform` | `validateAppFile` | Covered |
| iOS: require `iphonesimulator` | `platformName != REQUIRED_PLATFORM_NAME` | `require(platformName == "iphonesimulator")` | Covered |
| iOS: minimum OS version set | Validates `minimumOSVersion != null` | Plist parser throws if missing | Covered (different mechanism) |
| iOS: min version vs configured OS | `validateMinimumDeploymentTargetVersion` — rejects if app min > simulator version | Not checked | **Gap** |
| Android: arm64-v8a required | Checks `supportedArchitectures` contains arm64 | `require("arm64-v8a" in supportedArchitectures)` | Covered |
| Android: supported API level | Validates against `SupportedDevices.osVersionIds` | Not checked | **Gap** |
| Android: framework detection | `detectFramework` | Not present | Not validation (metadata only) |
| Web metadata | `getWebMetadata` | `getWebMetadata` | Covered |

## DeviceSpec Validation

| Check | Backend | CLI | Status |
|-------|---------|-----|--------|
| `DeviceSpec.fromRequest()` | Called | Called | Covered |
| `.validate()` on resolved DeviceSpec | Called — validates model/os against `SupportedDevices` | Not called | **Gap** |

## Gaps Summary

| # | Gap | Impact | Priority |
|---|-----|--------|----------|
| 1 | iOS minimum deployment target vs configured OS version | User uploads an app with min version higher than the selected simulator — fails on worker instead of at upload time | High |
| 2 | Android API level validation against supported devices | User selects an unsupported API level — fails on worker instead of at upload time | High |
| 3 | `DeviceSpec.validate()` not called in CLI | User specifies unsupported device model/OS combination — fails on worker instead of at upload time | High |
| 4 | Deprecated command detection | Deprecated commands still execute but could produce unexpected results | Low |
| 5 | `excludeNotSupportedCommands` not applied | Commands unsupported in cloud context are not filtered — may cause worker-side errors | Low |

## Recommendation

The CLI now covers all fundamental validations: platform inference, simulator build check, architecture check, workspace flow matching, name conflicts, and missing launchApp. These are the most common user errors.

**Safe to ship CLI validation now.** Gaps 1–3 should be closed before removing backend validation. Gaps 4–5 are low priority — deprecated/unsupported commands still produce backend-side errors that are not silent failures.

### Migration Plan

1. Ship CLI with current validations (this PR)
2. Close gaps 1–3 (DeviceSpec.validate, iOS min version, Android API level)
3. Monitor backend error rates — once gap errors drop to near-zero, all users are on the new CLI
4. Remove duplicated validation from backend `RunMaestroRoute`
