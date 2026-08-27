// NEGATIVE FIXTURE — indirect invocation attempt via public surface lookup. Must fail:
// getMethod("saveEditForm") throws NoSuchMethodException because the member is private.
fun indirectSave(actions: co.sanaa.agent.actions.AccessibilityActions): Any =
    actions.javaClass.getMethod("saveEditForm")
