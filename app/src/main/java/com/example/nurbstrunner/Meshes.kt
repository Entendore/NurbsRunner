package com.example.nurbstrunner

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MeshData(val verts: FloatArray, val idx: ShortArray)

/** VAO-backed mesh. Layout: pos(3) normal(3) uv(2) = 32-byte stride. */
class GpuMesh {
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var count = 0
    private var fbuf: FloatBuffer? = null      // reused for dynamic uploads
    private var sbufCache: ShortBuffer? = null

    fun upload(data: MeshData) {
        if (vao == 0) {
            val v = IntArray(1); GLES30.glGenVertexArrays(1, v, 0); vao = v[0]
            val b = IntArray(2); GLES30.glGenBuffers(2, b, 0); vbo = b[0]; ibo = b[1]
        }
        count = data.idx.size
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.verts.size * 4,
            fbuf(data.verts), GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.idx.size * 2,
            sbuf(data.idx), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
        GLES30.glBindVertexArray(0)
    }

    /** Re-upload vertex data in place — no allocation after warm-up. */
    fun updateVerts(verts: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, verts.size * 4, fbuf(verts))
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun fbuf(a: FloatArray): FloatBuffer {
        var fb = fbuf
        if (fb == null || fb.capacity() < a.size) {
            fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fbuf = fb
        }
        fb.clear(); fb.put(a); fb.position(0); fb.limit(a.size)
        return fb
    }

    private fun sbuf(a: ShortArray): ShortBuffer {
        var sb = sbufCache
        if (sb == null || sb.capacity() < a.size) {
            sb = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            sbufCache = sb
        }
        sb.clear(); sb.put(a); sb.position(0); sb.limit(a.size)
        return sb
    }
}

object Meshes {

    private fun push(v: ArrayList<Float>, x: Float, y: Float, z: Float,
                     nx: Float, ny: Float, nz: Float, u: Float = 0f, w: Float = 0f) {
        v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(w)
    }

    fun cube(): MeshData {
        val v = ArrayList<Float>(192)
        val s = 0.5f
        face(v,  s, -s, -s,  s, -s,  s,  s,  s,  s,  s,  s, -s,  1f, 0f, 0f)
        face(v, -s, -s,  s, -s, -s, -s, -s,  s, -s, -s,  s,  s, -1f, 0f, 0f)
        face(v, -s,  s, -s, -s,  s,  s,  s,  s,  s,  s,  s, -s,  0f, 1f, 0f)
        face(v, -s, -s,  s, -s, -s, -s,  s, -s, -s,  s, -s,  s,  0f, -1f, 0f)
        face(v, -s, -s,  s, -s,  s,  s,  s,  s,  s,  s, -s,  s,  0f, 0f, 1f)
        face(v, -s,  s, -s, -s, -s, -s,  s, -s, -s,  s,  s, -s,  0f, 0f, -1f)
        return MeshData(FloatArray(v.size) { v[it] }, ShortArray(36) { it.toShort() })
    }

    private fun face(v: ArrayList<Float>,
                     ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                     cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
                     nx: Float, ny: Float, nz: Float) {
        val pts = arrayOf(floatArrayOf(ax, ay, az), floatArrayOf(bx, by, bz),
                          floatArrayOf(cx, cy, cz), floatArrayOf(dx, dy, dz))
        for (o in intArrayOf(0, 1, 2, 0, 2, 3)) {
            val p = pts[o]
            push(v, p[0], p[1], p[2], nx, ny, nz)
        }
    }

    fun sphere(stacks: Int = 16, slices: Int = 28): MeshData {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (i in 0..stacks) {
            val phi = PI.toFloat() * i / stacks
            val y = cos(phi); val r = sin(phi)
            for (j in 0 until slices) {
                val th = 2f * PI.toFloat() * j / slices
                val nx = r * cos(th); val nz = r * sin(th)
                push(verts, nx, y, nz, nx, y, nz, j.toFloat() / slices, i.toFloat() / stacks)
            }
        }
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val j1 = (j + 1) % slices
                idx.add((i * slices + j).toShort())
                idx.add(((i + 1) * slices + j).toShort())
                idx.add((i * slices + j1).toShort())
                idx.add((i * slices + j1).toShort())
                idx.add(((i + 1) * slices + j).toShort())
                idx.add(((i + 1) * slices + j1).toShort())
            }
        }
        return MeshData(verts.toFloatArray(), idx.toShortArray())
    }

    fun torus(major: Float, minor: Float, seg1: Int = 48, seg2: Int = 14): MeshData {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (i in 0 until seg1) {
            val a = 2f * PI.toFloat() * i / seg1
            val ca = cos(a); val sa = sin(a)
            for (j in 0 until seg2) {
                val b = 2f * PI.toFloat() * j / seg2
                val cb = cos(b); val sb = sin(b)
                val r = major + minor * cb
                push(verts, ca * r, sa * r, minor * sb, ca * cb, sa * cb, sb)
            }
        }
        for (i in 0 until seg1) {
            val i1 = (i + 1) % seg1
            for (j in 0 until seg2) {
                val j1 = (j + 1) % seg2
                idx.add((i * seg2 + j).toShort())
                idx.add((i1 * seg2 + j).toShort())
                idx.add((i * seg2 + j1).toShort())
                idx.add((i * seg2 + j1).toShort())
                idx.add((i1 * seg2 + j).toShort())
                idx.add((i1 * seg2 + j1).toShort())
            }
        }
        return MeshData(verts.toFloatArray(), idx.toShortArray())
    }

    /** Flat XZ disc, radius 1 — used for the soft shadow blob (uFade in prop shader). */
    fun disc(segments: Int = 28): MeshData {
        val verts = ArrayList<Float>((segments + 1) * 8 * 3)
        val idx = ArrayList<Short>(segments * 3)
        push(verts, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        for (i in 0 until segments) {
            val a0 = 2.0 * PI * i / segments
            val a1 = 2.0 * PI * (i + 1) / segments
            push(verts, cos(a0).toFloat(), 0f, sin(a0).toFloat(), 0f, 1f, 0f)
            push(verts, cos(a1).toFloat(), 0f, sin(a1).toFloat(), 0f, 1f, 0f)
            idx.add(0)
            idx.add((verts.size / 8 - 2).toShort())
            idx.add((verts.size / 8 - 1).toShort())
        }
        return MeshData(verts.toFloatArray(), idx.toShortArray())
    }
}
