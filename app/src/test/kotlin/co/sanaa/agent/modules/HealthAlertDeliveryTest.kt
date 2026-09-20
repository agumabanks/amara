package co.sanaa.agent.modules
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HealthAlertDeliveryTest {
 @Test fun acknowledgedConditionsDoNotRealertWhenAnotherConditionChanges() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("health_alert_delivery",0).edit().clear().commit()
  val store=HealthAlertDelivery(context)
  store.record(listOf("Battery 7%", "WhatsApp held"))
  val restarted=HealthAlertDelivery(context)
  assertEquals(listOf("No internet"),restarted.unseen(listOf("Battery 8%","WhatsApp held","No internet")))
  assertTrue(restarted.unseen(listOf("WhatsApp held")).isEmpty())
 }
}
