package co.sanaa.agent.core
import org.junit.Assert.*
import org.junit.Test
class DeviceSurfaceBoundsTest {
    @Test fun coordinatesMustBelongToTheCurrentDeviceWindow() {
        assertTrue(DeviceSurfaceBounds.contains(0,40,800,1216,710,1169))
        assertFalse(DeviceSurfaceBounds.contains(0,40,800,1216,900,1500))
        assertTrue(DeviceSurfaceBounds.contains(100,0,1216,800,1100,700))
        assertFalse(DeviceSurfaceBounds.contains(100,0,1216,800,50,700))
        assertFalse(DeviceSurfaceBounds.contains(0,0,0,0,0,0))
        assertFalse(DeviceSurfaceBounds.contains(0,0,800,1216,800,1216))
    }
}
