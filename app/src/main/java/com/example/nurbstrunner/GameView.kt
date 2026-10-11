package com.example.nurbstrunner

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs

class GameView(context: Context, private val world: GameWorld) : GLSurfaceView(context) {

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var pointerId = -1   // multi-touch: only the gesture finger is tracked

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(e.actionIndex)
                downX = e.x; downY = e.y; downT = System.nanoTime()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (e.getPointerId(e.actionIndex) != pointerId) return true
                pointerId = -1
                val dx = e.x - downX
                val dy = e.y - downY
                val dtMs = (System.nanoTime() - downT) / 1_000_000L
                if (world.gameOver) {
                    // lineage-branch screen: only the buttons act
                    if (!world.branchChoiceActive) world.queue(Input.RESTART)
                    return true
                }
                if (dx * dx + dy * dy > 3600f && dtMs < 600) {
                    if (abs(dx) > abs(dy)) world.queue(if (dx > 0) Input.RIGHT else Input.LEFT)
                    else world.queue(if (dy < 0) Input.JUMP else Input.DIVE)
                } else if (dtMs < 250) {
                    world.queue(Input.JUMP)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_CANCEL -> { pointerId = -1; return true }
        }
        return super.onTouchEvent(e)
    }
}
