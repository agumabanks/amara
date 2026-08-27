# Side-effect boundary negative fixtures

These snippets are the canonical violation attempts for the structural boundary. They are
NOT compiled into the app (that is the point): each one references a member that is now
`private` inside AccessibilityActions, so applying it to production code would fail Kotlin
compilation. SideEffectBoundaryStructureTest proves the referenced members are absent from
the public API surface, which is exactly why wrapper, alias, callable-reference, and
indirect invocation attempts cannot resolve.

1. wrapper — re-exposing a primitive as a public helper.
2. alias — importing/renaming through a delegate object.
3. callable-reference — `AccessibilityActions::sendInCurrentChat`.
4. indirect — reflection-style lookup of a public method handle.
