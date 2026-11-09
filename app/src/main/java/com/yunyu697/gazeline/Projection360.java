package com.yunyu697.gazeline;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

/**
 * 360 equirectangular pipeline. Its own FOV, lens coefficients and IPD; these
 * defaults are not shared with the SBS pipeline.
 */
final class Projection360 {

    static final float DEFAULT_FOV_Y = 90f;
    static final float DEFAULT_K1 = -0.20f;
    static final float DEFAULT_K2 = 0.032f;
    static final float DEFAULT_IPD_DEG = 0f; // mono equirect: no artificial parallax

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vUv = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform mat3 uRot;\n" +
            "uniform float uK1;\n" +
            "uniform float uK2;\n" +
            "uniform float uTanHalfY;\n" +
            "uniform float uAspect;\n" +
            "uniform float uEyeShift;\n" +
            "uniform float uEyeShiftY;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vec2 p = vUv * 2.0 - 1.0;\n" +
            "  float r2 = dot(p, p);\n" +
            "  vec2 q = p * (1.0 + uK1 * r2 + uK2 * r2 * r2);\n" +
            "  if (q.x < -1.0 || q.x > 1.0 || q.y < -1.0 || q.y > 1.0) discard;\n" +
            "  vec3 d = normalize(vec3(q.x * uTanHalfY * uAspect + uEyeShift, q.y * uTanHalfY + uEyeShiftY, -1.0));\n" +
            "  vec3 w = uRot * d;\n" +
            "  float lon = atan(w.x, -w.z);\n" +
            "  float lat = asin(clamp(w.y, -1.0, 1.0));\n" +
            "  vec2 uv = vec2(lon * 0.15915494 + 0.5, lat * 0.31830989 + 0.5);\n" +
            "  gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy);\n" +
            "}\n";

    private final int program;
    private final int aPos;
    private final int uTex;
    private final int uTexMatrix;
    private final int uRot;
    private final int uK1;
    private final int uK2;
    private final int uTanHalfY;
    private final int uAspect;
    private final int uEyeShift;
    private final int uEyeShiftY;

    private float k1 = DEFAULT_K1;
    private float k2 = DEFAULT_K2;
    private float vertLeftDeg;
    private float vertRightDeg;

    Projection360() {
        program = GlUtil.link(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GlUtil.uniform(program, "uTex");
        uTexMatrix = GlUtil.uniform(program, "uTexMatrix");
        uRot = GlUtil.uniform(program, "uRot");
        uK1 = GlUtil.uniform(program, "uK1");
        uK2 = GlUtil.uniform(program, "uK2");
        uTanHalfY = GlUtil.uniform(program, "uTanHalfY");
        uAspect = GlUtil.uniform(program, "uAspect");
        uEyeShift = GlUtil.uniform(program, "uEyeShift");
        uEyeShiftY = GlUtil.uniform(program, "uEyeShiftY");
    }

    void setLens(float k1, float k2) {
        this.k1 = k1;
        this.k2 = k2;
    }

    /**
     * §1.12 applied to the mono panorama: k1/k2 and the per-eye vertical trim
     * are honoured, IPD is not — a single equirectangular image has no parallax
     * to offset, so pretending to shift it would only blur the horizon.
     */
    void setCalib(CalibParams p) {
        setLens(p.k1, p.k2);
        vertLeftDeg = p.vertLeftDeg;
        vertRightDeg = p.vertRightDeg;
    }

    void draw(int textureId, float[] texMatrix, float[] rotRowMajor, float aspect,
              int eye, FloatBuffer quad) {
        GLES20.glUseProgram(program);

        // 90° horizontal field of view: the eye viewport's aspect then fixes the
        // vertical FOV, so the HUD's angle table and the content use one scale.
        float tanHalfY = (float) Math.tan(Math.toRadians(DEFAULT_FOV_Y / 2f)) / aspect;
        float eyeShift = eye == 0 ? -1f : 1f;
        eyeShift *= (float) Math.tan(Math.toRadians(DEFAULT_IPD_DEG / 2f));
        float vertDeg = eye == 0 ? vertLeftDeg : vertRightDeg;
        float eyeShiftY = (float) Math.tan(Math.toRadians(vertDeg));

        GLES20.glUniform1f(uK1, k1);
        GLES20.glUniform1f(uK2, k2);
        GLES20.glUniform1f(uTanHalfY, tanHalfY);
        GLES20.glUniform1f(uAspect, aspect);
        GLES20.glUniform1f(uEyeShift, eyeShift);
        GLES20.glUniform1f(uEyeShiftY, eyeShiftY);
        GLES20.glUniformMatrix3fv(uRot, 1, true, rotRowMajor, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTex, 0);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    void release() {
        GLES20.glDeleteProgram(program);
    }
}
