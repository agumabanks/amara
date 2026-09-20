package co.sanaa.agent.core

/** Called only inside an admitted screen session. Reopening Terminal is a read-only recovery. */
class ShopSessionRecovery<T>(
    private val read: suspend () -> T,
    private val reopen: suspend () -> Boolean,
    private val settle: suspend () -> Unit,
) {
    suspend fun ensure(): T {
        try { return read() } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(_: Exception) { /* The foreground app can restore its existing authenticated publisher. */ }
        check(reopen()) { "Terminal session recovery could not open the installed app; no business action attempted" }
        var failure: Exception? = null
        repeat(3) {
            settle()
            try { return read() } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(error: Exception) { failure=error }
        }
        throw IllegalStateException("Terminal session still unavailable after reopening; open Terminal and complete sign-in or shop selection. No business action attempted.",failure)
    }
}
