package org.carbridge.extension.splitscreen.payload;

import static org.carbridge.extension.splitscreen.payload.AppScreen.TAG;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws the virtual display into the visible part of the car's map surface. In split screen the
 * surface spans the whole screen and Android Auto's cards cover part of it, so the display can't
 * render into the surface directly. Plain EGL: a bad car surface is an error code, where the
 * hardware Canvas path aborts the process.
 */
final class Compositor {
    private static final String VERTEX_SHADER =
        "attribute vec4 aPos;\n"
            + "attribute vec4 aTex;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = aPos;\n"
            + "  vTex = (uTexMatrix * aTex).xy;\n"
            + "}\n";
    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTex;\n"
            + "uniform samplerExternalOES sTex;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTex, vTex);\n"
            + "}\n";
    // Triangle strip covering the viewport, with matching texture coordinates
    private static final int OUTPUT_ATTEMPTS = 8;
    private static final long OUTPUT_RETRY_MS = 250;
    private static final float[] QUAD = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};

    private final HandlerThread thread = new HandlerThread("CarBridgeCompositor");
    private final Handler handler;
    private final SurfaceTexture texture = new SurfaceTexture(false);
    private final Surface displaySurface = new Surface(texture);
    private final float[] texMatrix = new float[16];
    private final Rect area = new Rect();
    private final FloatBuffer quad;
    private final Runnable connectOutput = this::connectOutput;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onOutputStuck;

    // Owned by the compositor thread
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program;
    private boolean ready;
    private boolean frameAvailable;
    @Nullable private Surface output;
    private int outputAttempts;

    /** {@code onOutputStuck} runs on the main thread when the car surface can't be drawn into. */
    Compositor(@NonNull Runnable onOutputStuck) {
        this.onOutputStuck = onOutputStuck;
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::initGl);
        texture.setOnFrameAvailableListener(t -> {
            frameAvailable = true;
            draw();
        }, handler);
    }

    /** Sizes the surface the virtual display renders into, and returns it. */
    @NonNull
    Surface configure(int width, int height) {
        texture.setDefaultBufferSize(width, height);
        return displaySurface;
    }

    void setOutput(@Nullable Surface surface) {
        handler.post(() -> {
            output = surface;
            outputAttempts = 0;
            handler.removeCallbacks(connectOutput);
            destroyWindow();
            connectOutput.run();
        });
    }

    private void connectOutput() {
        Surface surface = output;
        if (surface != null && surface.isValid() && ready && window == EGL14.EGL_NO_SURFACE) {
            window = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, new int[] {EGL14.EGL_NONE}, 0);
            if (window == null || window == EGL14.EGL_NO_SURFACE) {
                window = EGL14.EGL_NO_SURFACE;
                int error = EGL14.eglGetError();
                // Android Auto reuses the surface when it restarts a dead app process, and the
                // dead process stays connected to it; only a new surface from Android Auto helps
                if (++outputAttempts < OUTPUT_ATTEMPTS) {
                    handler.postDelayed(connectOutput, OUTPUT_RETRY_MS);
                } else {
                    Log.w(TAG, "Car surface rejected: EGL error " + error);
                    main.post(onOutputStuck);
                }
            }
        }
        draw();
    }

    void setArea(@NonNull Rect visibleArea) {
        Rect copy = new Rect(visibleArea);
        handler.post(() -> {
            area.set(copy);
            draw();
        });
    }

    void release() {
        handler.post(() -> {
            output = null;
            handler.removeCallbacks(connectOutput);
            main.removeCallbacks(onOutputStuck);
            destroyWindow();
            if (ready) {
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
                texture.detachFromGLContext();
                GLES20.glDeleteProgram(program);
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, pbuffer);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
                ready = false;
            }
            texture.release();
            displaySurface.release();
        });
        thread.quitSafely();
    }

    private void initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed: " + EGL14.eglGetError());
            return;
        }
        int[] attributes = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.e(TAG, "No EGL config: " + EGL14.eglGetError());
            return;
        }
        eglConfig = configs[0];
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        // A context needs a surface to be current before the car surface exists
        pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
            new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT || pbuffer == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) {
            Log.e(TAG, "EGL context setup failed: " + EGL14.eglGetError());
            return;
        }
        program = buildProgram();
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture.attachToGLContext(tex[0]);
        ready = program != 0;
    }

    private void draw() {
        if (!ready) return;
        boolean hasWindow = window != EGL14.EGL_NO_SURFACE;
        EGLSurface target = hasWindow ? window : pbuffer;
        if (!EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) {
            Log.w(TAG, "eglMakeCurrent failed: " + EGL14.eglGetError());
            destroyWindow();
            return;
        }
        if (frameAvailable) {
            frameAvailable = false;
            // Consume frames even with no car surface, or the display stalls
            texture.updateTexImage();
            texture.getTransformMatrix(texMatrix);
        }
        if (!hasWindow) return;

        int[] size = new int[2];
        EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_HEIGHT, size, 1);
        GLES20.glViewport(0, 0, size[0], size[1]);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!area.isEmpty()) {
            // GL's origin is bottom-left; the area is in top-left surface coordinates
            GLES20.glViewport(area.left, size[1] - area.bottom, area.width(), area.height());
            drawQuad();
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, window)) {
            Log.w(TAG, "eglSwapBuffers failed: " + EGL14.eglGetError());
            destroyWindow();
        }
    }

    private void drawQuad() {
        GLES20.glUseProgram(program);
        int pos = GLES20.glGetAttribLocation(program, "aPos");
        int tex = GLES20.glGetAttribLocation(program, "aTex");
        quad.position(0);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(pos);
        quad.position(2);
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(tex);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
        EGL14.eglDestroySurface(eglDisplay, window);
        window = EGL14.EGL_NO_SURFACE;
    }

    private static int buildProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "Shader link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
