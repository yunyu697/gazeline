package com.yunyu697.gazeline;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

/**
 * SBS half-width pipeline: each eye samples one half of the frame and the pair
 * is mapped onto a virtual screen in front of the viewer. Its own FOV, lens
 * coefficients and screen width; deliberately not shared with the 360 pipeline.
 */
final class ProjectionSbs {

    static final float DEFAULT_FOV_Y = 85f;
    static final float DEFAULT_K1 = -0.26f;
    static final float DEFAULT_K2 = 0.045f;
    static final float DEFAULT_SCREEN_WIDTH_DEG = 70f;

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
            "uniform float uK1;\n" +
            "uniform float uK2;\n" +
            "uniform float uTanHalfY;\n" +
            "uniform float uAspect;\n" +
            "uniform float uTanScreenHalfX;\n" +
            "uniform float uTanScreenHalfY;\n" +
            "uniform float uEyeHalf;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vec2 p = vUv * 2.0 - 1.0;\n" +
            "  float r2 = dot(p, p);\n" +
            "  vec2 q = p * (1.0 + uK1 * r2 + uK2 * r2 * r2);\n" +
            "  float tx = q.x * uTanHalfY * uAspect;\n" +
            "  float ty = q.y * uTanHalfY;\n" +
            "  if (abs(tx) > uTanScreenHalfX || abs(ty) > uTanScreenHalfY) {\n" +
            "    gl_FragColor = vec4(0.012, 0.014, 0.020, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  vec2 luv = vec2((tx + uTanScreenHalfX) / (2.0 * uTanScreenHalfX),\n" +
            "                  (ty + uTanScreenHalfY) / (2.0 * uTanScreenHalfY));\n" +
            "  vec2 uv = vec2((uEyeHalf + luv.x) * 0.5, luv.y);\n" +
            "  gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy);\n" +
            "}\n";

    private final int program;
    private final int aPos;
    private final int uTex;
    private final int uTexMatrix;
    private final int uK1;
    private final int uK2;
    private final int uTanHalfY;
    private final int uAspect;
    private final int uTanScreenHalfX;
    private final int uTanScreenHalfY;
    private final int uEyeHalf;

    private float k1 = DEFAULT_K1;
    private float k2 = DEFAULT_K2;

    ProjectionSbs() {
        program = GlUtil.link(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GlUtil.uniform(program, "uTex");
        uTexMatrix = GlUtil.uniform(program, "uTexMatrix");
        uK1 = GlUtil.uniform(program, "uK1");
        uK2 = GlUtil.uniform(program, "uK2");
        uTanHalfY = GlUtil.uniform(program, "uTanHalfY");
        uAspect = GlUtil.uniform(program, "uAspect");
        uTanScreenHalfX = GlUtil.uniform(program, "uTanScreenHalfX");
        uTanScreenHalfY = GlUtil.uniform(program, "uTanScreenHalfY");
        uEyeHalf = GlUtil.uniform(program, "uEyeHalf");
    }

    void setLens(float k1, float k2) {
        this.k1 = k1;
        this.k2 = k2;
    }

    void draw(int textureId, float[] texMatrix, float viewportAspect, int eye,
              int videoWidth, int videoHeight, FloatBuffer quad) {
        GLES20.glUseProgram(program);

        float tanHalfY = (float) Math.tan(Math.toRadians(DEFAULT_FOV_Y / 2f));
        float tanScreenHalfX = (float) Math.tan(Math.toRadians(DEFAULT_SCREEN_WIDTH_DEG / 2f));
        float videoAspect = (videoWidth > 0 && videoHeight > 0)
                ? (videoWidth * 0.5f) / videoHeight : 16f / 9f;
        float tanScreenHalfY = tanScreenHalfX / videoAspect;

        GLES20.glUniform1f(uK1, k1);
        GLES20.glUniform1f(uK2, k2);
        GLES20.glUniform1f(uTanHalfY, tanHalfY);
        GLES20.glUniform1f(uAspect, viewportAspect);
        GLES20.glUniform1f(uTanScreenHalfX, tanScreenHalfX);
        GLES20.glUniform1f(uTanScreenHalfY, tanScreenHalfY);
        GLES20.glUniform1f(uEyeHalf, eye == 0 ? 0f : 1f);

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
