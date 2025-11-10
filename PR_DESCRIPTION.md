# Add Configurable Custom Identifier Support for Web Testing

## Summary

This PR introduces a flexible, configuration-driven identifier system for web element selection in Maestro. Instead of hardcoding specific HTML attributes, users can now configure any HTML attribute to YAML selector key mappings.

## Motivation

Web frameworks (Flutter Web, React, Vue, etc.) use different HTML attributes for element identification. Previously, adding support for each framework required code changes. This PR makes Maestro extensible without any code modifications.

## Implementation

### 1. Configuration Model (`MaestroConfig.kt`)

Added `IdentifierConfig` to allow users to define HTML attribute → YAML key mappings:

```kotlin
data class IdentifierConfig(
    val mappings: Map<String, String> = emptyMap()
)
```

### 2. YAML Configuration

Users can now configure custom identifiers in their flow files:

```yaml
identifierConfig:
  flt-semantics-identifier: flutterId      # Flutter Web
  data-testid: testId                       # Testing frameworks
  data-qa: qaId                             # QA automation
  custom-attr: customAttr                   # Custom frameworks
---
- tapOn:
    testId: submit-button
```

### 3. Dynamic Attribute Extraction (`maestro-web.js`)

- Extracts ONLY user-configured HTML attributes (no assumptions)
- Stores attributes with both HTML and YAML names for flexibility
- No hardcoded prefixes like `data-*` or `flt-*`
- Configuration-driven: only processes what users explicitly specify

### 4. Hybrid Attribute Parsing (`WebDriver.kt`, `CdpWebDriver.kt`)

Implements a two-phase parsing strategy for optimal balance:

**Phase 1: Known Attributes (Explicit Type Safety)**
- Processes core Maestro attributes with explicit type casting
- Ensures compile-time safety for: `text`, `bounds`, `resource-id`, `selected`, `synthetic`, `ignoreBoundsFiltering`
- Backwards compatible with existing Maestro internals

**Phase 2: Custom Identifiers (Dynamic Extension)**
- Iterates through remaining JavaScript attributes
- Dynamically adds any attributes not already processed
- Supports user-configured identifiers without code changes
- Prevents overriding known attributes via containsKey check

**Why This Approach:**
```kotlin
// Explicit: Known attributes with type safety
val attributes = mutableMapOf(
    "text" to attrs["text"] as String,
    "bounds" to attrs["bounds"] as String,
)

// Dynamic: Custom identifiers without hardcoding
attrs.forEach { (key, value) ->
    if (!attributes.containsKey(key)) {
        when (value) {
            is String -> attributes[key] = value
            // ... handle other types
        }
    }
}
```

Result: **Type-safe core + extensible custom identifiers**

### 5. Configurable Filtering (`Orchestra.kt`, `Filters.kt`)

- Added `customIdentifierMatches()` function
- Maps YAML keys to HTML attributes using `identifierConfig`
- Generic implementation works with any attribute pattern

## Example Use Cases

### Flutter Web
```yaml
identifierConfig:
  flt-semantics-identifier: flutterId
---
- tapOn:
    flutterId: myWidget
```

### React Testing Library
```yaml
identifierConfig:
  data-testid: testId
---
- tapOn:
    testId: submit-button
```

### Custom Framework
```yaml
identifierConfig:
  custom-id: customId
  app-component: componentId
---
- tapOn:
    componentId: header-nav
```

## Benefits

1. **Framework Agnostic**: No bias toward any specific framework
2. **Extensible**: Support any HTML attribute without code changes
3. **User-Driven**: Explicit configuration over implicit conventions
4. **Type-Safe**: Known attributes maintain explicit type checking
5. **Backwards Compatible**: Existing code continues to work unchanged
6. **Hybrid Architecture**: Safety for core attributes, flexibility for custom ones
7. **Future-Proof**: Easy to adapt to new frameworks

## Technical Details

### Files Modified (10 core files)

**Data Models:**
- `maestro-orchestra-models/src/main/java/maestro/orchestra/MaestroConfig.kt` - Added IdentifierConfig
- `maestro-orchestra-models/src/main/java/maestro/orchestra/ElementSelector.kt` - Added customIdentifiers field

**YAML Parsing:**
- `maestro-orchestra/src/main/java/maestro/orchestra/yaml/YamlConfig.kt` - Parse identifierConfig from YAML
- `maestro-orchestra/src/main/java/maestro/orchestra/yaml/YamlElementSelector.kt` - Capture custom fields with @JsonAnySetter
- `maestro-orchestra/src/main/java/maestro/orchestra/yaml/YamlFluentCommand.kt` - Convert custom fields to ElementSelector

**Execution:**
- `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` - Pass config to drivers, map YAML keys to HTML attributes
- `maestro-client/src/main/java/maestro/Filters.kt` - Added customIdentifierMatches function

**Browser Integration:**
- `maestro-client/src/main/java/maestro/drivers/WebDriver.kt` - Inject config, hybrid attribute parsing
- `maestro-client/src/main/java/maestro/drivers/CdpWebDriver.kt` - Inject config, hybrid attribute parsing  
- `maestro-client/src/main/resources/maestro-web.js` - Extract configured attributes only

### Hybrid Attribute Parsing Strategy

The WebDriver and CdpWebDriver implementations use a two-phase parsing approach:

1. **Phase 1**: Process known Maestro attributes with explicit type checking
   - Ensures backwards compatibility
   - Maintains type safety for core attributes (`text`, `bounds`, `resource-id`, `selected`, `synthetic`, `ignoreBoundsFiltering`)
   - Prevents runtime type errors

2. **Phase 2**: Dynamically add any additional attributes from JavaScript
   - Supports user-configured custom identifiers
   - No hardcoding of new attribute names needed
   - Skips attributes already processed in Phase 1 (prevents overrides)

This hybrid approach provides the best of both worlds: safety for known attributes and flexibility for custom identifiers.

### Changes Summary

- **10 files changed**
- **152 insertions, 2 deletions**
- No breaking changes
- Backwards compatible (pure opt-in, no defaults)
- Type-safe for known attributes, extensible for custom ones

## Testing

Tested with Flutter Web application using `flt-semantics-identifier` attributes. All tests passing.

## Breaking Changes

None. This is a purely additive feature. Existing flows continue to work unchanged.

## Documentation

The configuration syntax is straightforward and follows existing Maestro patterns:

```yaml
identifierConfig:
  <html-attribute-name>: <yaml-selector-key>
```

Users can configure as many mappings as needed for their specific use case.

## Future Enhancements

This foundation enables:
- Support for any web framework's identifier patterns
- Custom automation tooling integration
- Framework-specific Maestro extensions
- Community-driven selector configurations

