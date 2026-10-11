package com.example.nurbstrunner

import android.opengl.GLES30

class Program(val id: Int) {
    private val cache = HashMap<String, Int>()
    fun loc(name: String): Int = cache.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun use() = GLES30.glUseProgram(id)
}

object Shaders {

    // ---- shared prop/sky vertex: pos + normal, world pos + local pos out ----
    const val VERTEX = """#version 300 es
precision mediump float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vWorld;
out vec3 vLocal;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vLocal = aPos;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    // ---- props: obstacles, orbs, gates, decor, particles, shadow blob ----
    const val FRAGMENT = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorld;
in vec3 vLocal;
uniform vec3 uColor;
uniform vec3 uLightDir;
uniform vec3 uEye;
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;
uniform float uEmissive;
uniform float uRim;
uniform float uTime;
uniform float uAlpha;
uniform float uFade;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 l = normalize(uLightDir);
    vec3 v = normalize(uEye - vWorld);
    float diff = max(dot(n, l), 0.0);
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), 40.0) * 0.45;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5) * uRim;
    float pulse = 0.75 + 0.25 * sin(uTime * 5.0);
    vec3 col = uColor * (0.30 + 0.72 * diff) + vec3(spec)
             + uColor * rim + uColor * uEmissive * pulse;
    float d = length(uEye - vWorld);
    float fog = clamp((d - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
    float a = uAlpha;
    if (uFade > 0.5) a *= 1.0 - smoothstep(0.15, 0.95, length(vLocal.xz));
    fragColor = vec4(mix(col, uFogColor, fog), a);
}
"""

    // ---- evolved NURBS body ----
    const val BODY_VERTEX = """#version 300 es
precision mediump float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUV;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform float uTime;
uniform float uAmp;
uniform float uWFreq;
uniform float uWSpeed;
out vec3 vNormal;
out vec3 vWorld;
out vec2 vUV;
void main() {
    float w = sin(aUV.x * 6.28318 * uWFreq + uTime * uWSpeed) * uAmp * (0.3 + 0.7 * aUV.y);
    vec3 p = aPos + aNormal * w;
    vec4 wp = uModel * vec4(p, 1.0);
    vWorld = wp.xyz;
    vNormal = mat3(uModel) * aNormal;
    vUV = aUV;
    gl_Position = uMVP * vec4(p, 1.0);
}
"""

    const val BODY_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorld;
in vec2 vUV;
uniform vec3 uColA;
uniform vec3 uColB;
uniform float uPattern;
uniform float uPatFreq;
uniform float uPatCon;
uniform float uEmissive;
uniform float uPulse;
uniform float uSparkle;
uniform float uRim;
uniform float uFlash;
uniform float uAlpha;
uniform float uTime;
uniform vec3 uLightDir;
uniform vec3 uEye;
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;
out vec4 fragColor;
void main() {
    vec3 base = mix(uColA, uColB, vUV.y);

    float mask = 0.0;
    if (uPattern < 0.5) {
        mask = 0.0;
    } else if (uPattern < 1.5) {
        mask = 0.5 + 0.5 * sin(vUV.x * 6.28318 * uPatFreq + vUV.y * 9.0);
    } else if (uPattern < 2.5) {
        mask = 0.5 + 0.5 * sin(vUV.y * 6.28318 * uPatFreq * 0.5);
    } else {
        vec2 c = vec2(vUV.x * uPatFreq, vUV.y * uPatFreq * 0.7);
        mask = 1.0 - smoothstep(0.10, 0.30, length(fract(c) - 0.5));
    }
    vec3 alb = mix(base, base * 0.25 + vec3(0.08, 0.03, 0.14),
                   clamp(mask, 0.0, 1.0) * uPatCon);

    vec3 n = normalize(vNormal);
    vec3 l = normalize(uLightDir);
    vec3 v = normalize(uEye - vWorld);
    float diff = max(dot(n, l), 0.0);
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), 40.0) * 0.45;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5) * uRim;
    vec3 rimCol = mix(uColB, vec3(1.0), 0.35);
    vec3 irid = 0.5 + 0.5 * cos(6.28318 * (vUV.x * 2.0 + vUV.y * 3.0)
                                + uTime * 1.3 + vec3(0.0, 2.094, 4.188));
    float pulse = 0.5 + 0.5 * sin(uTime * uPulse - vUV.y * 5.0);

    vec3 col = alb * (0.28 + 0.72 * diff) + vec3(spec)
             + rimCol * rim
             + irid * pow(max(dot(n, h), 0.0), 8.0) * uSparkle
             + base * uEmissive * (0.30 + 0.70 * pulse)
             + alb * uFlash;

    float d = length(uEye - vWorld);
    float fog = clamp((d - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
    fragColor = vec4(mix(col, uFogColor, fog), uAlpha);
}
"""

    // ---- procedural track (positions pre-bent on CPU; uv.x lateral, uv.y = meters) ----
    const val TRACK_VERTEX = """#version 300 es
precision mediump float;
layout(location = 0) in vec3 aPos;
layout(location = 2) in vec2 aUV;
uniform mat4 uVP;
out vec3 vWorld;
out vec2 vUV;
void main() {
    vWorld = aPos;
    vUV = aUV;
    gl_Position = uVP * vec4(aPos, 1.0);
}
"""

    // uv.y < 0 marks a 3-lane fork branch; uv.x > 8 marks the VOID chasm strip.
    const val TRACK_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec2 vUV;
uniform vec3 uEye;
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;
uniform float uTime;
out vec4 fragColor;

void palette(int k, out vec3 rd, out vec3 rl, out vec3 aA, out vec3 aB, out vec3 eg) {
    if (k == 0)      { rd = vec3(0.045,0.055,0.105); rl = vec3(0.085,0.095,0.175); aA = vec3(0.10,0.85,1.00); aB = vec3(1.00,0.20,0.75); eg = vec3(0.15,0.65,1.00); }
    else if (k == 1) { rd = vec3(0.090,0.040,0.035); rl = vec3(0.160,0.070,0.050); aA = vec3(1.00,0.45,0.10); aB = vec3(1.00,0.75,0.15); eg = vec3(1.00,0.35,0.12); }
    else if (k == 2) { rd = vec3(0.060,0.085,0.115); rl = vec3(0.105,0.145,0.190); aA = vec3(0.65,0.95,1.00); aB = vec3(0.45,0.75,1.00); eg = vec3(0.70,0.92,1.00); }
    else if (k == 3) { rd = vec3(0.035,0.075,0.050); rl = vec3(0.065,0.130,0.085); aA = vec3(0.45,1.00,0.25); aB = vec3(0.85,1.00,0.30); eg = vec3(0.40,1.00,0.35); }
    else             { rd = vec3(0.040,0.030,0.070); rl = vec3(0.075,0.055,0.130); aA = vec3(0.70,0.35,1.00); aB = vec3(1.00,0.35,0.85); eg = vec3(0.60,0.40,1.00); }
}

void main() {
    float u = vUV.x;
    float raw = vUV.y;
    float d = abs(raw);          // negative uv.y marks a 3-lane fork branch
    float lanes = raw < 0.0 ? 3.0 : 5.0;

    float f = d / 300.0;
    int i0 = int(mod(floor(f), 5.0));
    float bl = smoothstep(0.85, 1.0, fract(f));
    vec3 rd1, rl1, aA1, aB1, e1; palette(i0, rd1, rl1, aA1, aB1, e1);
    vec3 rd2, rl2, aA2, aB2, e2; palette(int(mod(floor(f) + 1.0, 5.0)), rd2, rl2, aA2, aB2, e2);
    vec3 rD = mix(rd1, rd2, bl);
    vec3 rL = mix(rl1, rl2, bl);
    vec3 aA = mix(aA1, aA2, bl);
    vec3 aB = mix(aB1, aB2, bl);
    vec3 eg = mix(e1, e2, bl);

    vec3 col;
    if (u > 8.0) {
        // VOID strip between forked branches: dark pit with glowing rims
        float lu = u - 9.9;                       // -1..1 across the strip
        float edge = smoothstep(0.45, 1.0, abs(lu));
        float glow = 0.55 + 0.45 * sin(uTime * 2.4 - d * 0.25);
        col = vec3(0.012, 0.006, 0.030) + mix(aA, aB, 0.5) * edge * glow;
    } else if (abs(u) > 1.5) {
        col = rD * 0.30;                          // skirt under the road
    } else {
        float band = 0.5 + 0.5 * sin(d * 0.35);
        col = mix(rD, rL, band * 0.6);

        float td = abs(fract(d * 0.125) - 0.5);
        col += eg * smoothstep(0.045, 0.02, td) * 0.10;

        // lane dividers: 5-lane main at +/-0.1818/+/-0.5455, 3-lane branch at +/-0.3265
        float lw;
        if (lanes > 4.0) {
            lw = min(smoothstep(0.028, 0.011, abs(abs(u) - 0.1818)),
                     smoothstep(0.028, 0.011, abs(abs(u) - 0.5455)));
        } else {
            lw = smoothstep(0.028, 0.011, abs(abs(u) - 0.3265));
        }
        col += mix(aA, aB, 0.5) * lw * step(0.5, fract(d * 0.2)) * 0.55;

        float cl = smoothstep(0.020, 0.006, abs(u));
        col += aA * cl * 0.22;

        float er = smoothstep(0.050, 0.012, abs(abs(u) - 0.95));
        float ep = 0.7 + 0.3 * sin(uTime * 3.0 - d * 0.15);
        col += eg * er * (0.5 + 0.5 * ep);

        float sw = fract(d * 0.007143 - uTime * 0.12);
        col += mix(aA, aB, 0.5) * exp(-sw * sw * 300.0) * 0.45;
    }

    float dist = length(uEye - vWorld);
    float fog = clamp((dist - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
    fragColor = vec4(mix(col, uFogColor, fog), 1.0);
}
"""

    // ---- sky dome: zone gradient + stars + horizon glow ----
    const val SKY_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vLocal;
uniform vec3 uEye;
uniform vec3 uHorizon;
uniform vec3 uZenith;
uniform vec3 uAccent;
uniform float uTime;
out vec4 fragColor;
void main() {
    vec3 dir = normalize(vWorld - uEye);
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 col = mix(uHorizon, uZenith, pow(h, 0.75));
    col += uAccent * exp(-abs(dir.y) * 7.0) * 0.30;
    vec3 cell = floor(dir * 180.0);
    float rnd = fract(sin(dot(cell, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    float star = step(0.992, rnd) * (0.55 + 0.45 * sin(uTime * 2.5 + rnd * 60.0));
    col += vec3(star) * smoothstep(0.02, 0.25, dir.y);
    fragColor = vec4(col, 1.0);
}
"""

    // ---- graceful-degradation fallbacks (solid color, same uniform names) ----
    const val FALLBACK_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uEmissive;
uniform float uTime;
out vec4 fragColor;
void main() {
    vec3 c = uColor * (0.7 + 0.3 * sin(uTime * 3.0)) + uEmissive * 0.25;
    fragColor = vec4(c, uAlpha);
}
"""

    const val FALLBACK_TRACK_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec3 uFogColor;
out vec4 fragColor;
void main() { fragColor = vec4(uFogColor * 0.55, 1.0); }
"""

    /**
     * Builds [vsSrc]+[fsSrc]; on any failure logs and falls back to [fbSrc]
     * so a driver-specific shader bug degrades visuals instead of crashing.
     * Missing uniforms in Program.loc() return -1 and are ignored by GL.
     */
    fun buildSafe(vsSrc: String, fsSrc: String, fbSrc: String, tag: String): Int =
        try {
            build(vsSrc, fsSrc)
        } catch (t: Throwable) {
            Logx.e("Shader/$tag", "compile failed, using fallback: ${t.message}")
            try { build(vsSrc, fbSrc) } catch (t2: Throwable) {
                Logx.e("Shader/$tag", "fallback failed: ${t2.message}")
                throw t2
            }
        }

    fun build(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("Program link failed: $log")
        }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val status = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return sh
    }
}
