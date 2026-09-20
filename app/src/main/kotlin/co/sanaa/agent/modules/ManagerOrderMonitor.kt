package co.sanaa.agent.modules

import android.content.Context
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.work.ManagerReportWork
import co.sanaa.agent.core.work.WorkQueue

/** Initial scan is a baseline; report subsequent paid/delivered state changes once. */
class ManagerOrderMonitor(private val context: Context) {
    private val prefs=context.getSharedPreferences("manager_order_observations",Context.MODE_PRIVATE)
    suspend fun check(soko: SokoApiClient, target: String, queue: WorkQueue): Int {
        if(target.isBlank()) return 0
        val shop=co.sanaa.agent.core.TerminalShopIdentity.readFresh(context)
        val orders=soko.recentOrders().distinctBy { it.optString("id") }
        check(co.sanaa.agent.core.TerminalShopIdentity.readFresh(context).scope==shop.scope) { "Shop changed while reading orders" }
        val initialized=prefs.getBoolean("${shop.scope}:initialized",false)
        var offered=0
        for(order in orders) {
            val id=order.optString("id").takeIf { it.isNotBlank() } ?: continue
            val payment=order.optString("payment_status"); val delivery=order.optString("delivery_status")
            val state="$payment|$delivery"
            if(initialized && prefs.getString("${shop.scope}:$id",null)!=state && (payment=="paid" || delivery=="delivered")) {
                val report=ManagerReportWork.from(target,"soko-order:${shop.scope}:$id:$state",
                    "Soko order ${order.optString("code")}\nPayment: $payment\nDelivery: $delivery\nOrder total: UGX ${order.optLong("grand_total")}\nSource: current Soko Terminal order record.") ?: continue
                if(queue.offer(report)==WorkQueue.OfferResult.REJECTED_CAP) continue
                offered++
            }
            prefs.edit().putString("${shop.scope}:$id",state).commit()
        }
        prefs.edit().putBoolean("${shop.scope}:initialized",true).commit()
        return offered
    }
}
