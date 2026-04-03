# Fix: `containsChild` cross-hierarchy TreeNode equality failure

## Problem

The `relatives` e2e test (and any flow using `containsChild` with relative selectors) fails intermittently on real devices with:

```
Assertion is false: id: level-0, Contains child: id: level-1, Below "top side", 
Above "bottom side", Left of "right side", Right of "left side", Contains child: id: level-2 is visible
```

The test times out after 17 seconds despite all elements being visible and correctly positioned (confirmed by screenshot and hierarchy dump).

## Root Cause

`containsChild` in `Orchestra.buildFilter()` eagerly called `findElement()` to resolve the child element during filter construction:

```kotlin
// Orchestra.kt (before)
relativeFilters += Filters.containsChild(findElement(it, optional = false).element).asFilter()
```

`findElement` calls `findElementWithTimeout` which fetches a **fresh view hierarchy from the device** and returns a `TreeNode` from that snapshot. This captured `TreeNode` is then baked into the filter closure.

Later, when the outer `findElementWithTimeout` polls the device, it fetches a **new** hierarchy and runs the filter against it. The filter compares nodes from the new hierarchy against the captured `TreeNode` from the earlier hierarchy using `TreeNode.equals()`.

Since `TreeNode` is a `data class`, `equals()` performs a **deep recursive structural comparison** of the entire subtree — attributes maps, all children, their children, etc. On real Android devices, transient accessibility attributes (focused state, drawing order, etc.) can differ between hierarchy dumps. Any single attribute difference anywhere in the subtree causes the deep equality to fail, and the filter never matches.

```
Hierarchy fetch #1 (during buildFilter):
  level-1 → children: [text_node_v1, level-2]  ← captured

Hierarchy fetch #2 (during findElementWithTimeout polling):
  level-1 → children: [text_node_v2, level-2]  ← current
                        ^^^^^^^^^^^ transient attribute changed

level-1_current == level-1_captured?  → deep structural compare → FALSE
```

## Fix

**`Orchestra.kt`** — Replace eager `findElement` with `buildFilter` (no hierarchy fetch):

```kotlin
// Before (broken): fetches hierarchy, captures stale TreeNode
relativeFilters += Filters.containsChild(findElement(it, optional = false).element).asFilter()

// After (fixed): builds filter function, no hierarchy fetch
relativeFilters += Filters.containsChild(buildFilter(it).filterFunc)
```

**`Filters.kt`** — New filter-based `containsChild` overload:

```kotlin
fun containsChild(childFilter: ElementFilter): ElementFilter {
    return { nodes ->
        val matchingChildren = childFilter(nodes).toSet()
        nodes.filter { node ->
            node.children.any { child -> matchingChildren.contains(child) }
        }
    }
}
```

This evaluates everything against the **same** `nodes` list from the **same** hierarchy fetch. `childFilter(nodes)` finds matching children within the current hierarchy, and `node.children` are direct children from that same hierarchy. The `TreeNode` objects are identical references (from `aggregate()` flattening the tree), so `Set.contains` always works — no cross-hierarchy comparison needed.

This is consistent with how `containsDescendants` already works (filter-based, no eager `findElement`).

## Reproduction

Integration test `Case 139` reproduces the bug using `mutatingText` on a sibling element inside the `containsChild` target, simulating real Android transient attribute changes between hierarchy fetches:

- **Before fix**: `AssertionFailure` — filter never matches due to cross-hierarchy `TreeNode` inequality
- **After fix**: Test passes

## Files Changed

| File | Change |
|------|--------|
| `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` | Replace `findElement()` with `buildFilter()` in `containsChild` handling |
| `maestro-client/src/main/java/maestro/Filters.kt` | Add `containsChild(ElementFilter)` overload |
| `maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt` | Add `Case 139` integration test |
| `maestro-test/src/test/resources/139_contains_child_with_relative_position.yaml` | Test flow file |
