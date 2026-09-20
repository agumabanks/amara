package co.sanaa.agent.core

/** Current window bounds, including offsets in split-screen and rotated layouts. */
object DeviceSurfaceBounds {
    fun contains(left: Int, top: Int, right: Int, bottom: Int, x: Int, y: Int): Boolean =
        right>left && bottom>top && x>=left && x<right && y>=top && y<bottom
}
