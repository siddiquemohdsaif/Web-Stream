package com.w3n.webstream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.w3n.webstream.Util.CameraController;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

final class LocalVideoPreviewView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "ENCODER_PARVEZ";

    private final GlRenderer renderer = new GlRenderer();

    private HandlerThread renderThread;
    private Handler renderHandler;
    private int bitmapFrameCount;
    private int yuvFrameCount;

    LocalVideoPreviewView(Context context) {
        super(context);
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    void renderBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.d(TAG, "renderBitmap skipped: bitmap unavailable");
            return;
        }

        bitmapFrameCount += 1;
        if (shouldLog(bitmapFrameCount)) {
            Log.d(TAG, "Texture preview bitmap queued: count=" + bitmapFrameCount
                    + ", size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + ", pixels=" + describeBitmapSamples(bitmap));
        }
        postRender(() -> renderer.drawBitmap(bitmap, getWidth(), getHeight()));
    }

    void renderCameraFrame(CameraController.CameraFrame frame) {
        if (frame == null || frame.yuv420Data == null || frame.yuv420Data.length == 0) {
            Log.d(TAG, "renderCameraFrame skipped: empty frame/data");
            return;
        }

        byte[] yuvCopy = Arrays.copyOf(frame.yuv420Data, frame.yuv420Data.length);
        yuvFrameCount += 1;
        if (shouldLog(yuvFrameCount)) {
            Log.d(TAG, "Texture preview YUV queued: count=" + yuvFrameCount
                    + ", size=" + frame.width + "x" + frame.height
                    + ", format=" + frame.yuvFormat
                    + ", bytes=" + yuvCopy.length);
        }
        postRender(() -> renderer.drawYuv(
                yuvCopy,
                frame.width,
                frame.height,
                frame.yuvFormat,
                getWidth(),
                getHeight()));
    }

    void clearFrame() {
        Log.d(TAG, "Texture preview clearFrame");
        if (renderHandler != null) {
            postRender(renderer::clear);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "Texture preview surface available: " + width + "x" + height);
        ensureRenderThread();
        Surface surface = new Surface(surfaceTexture);
        postRender(() -> renderer.create(surface, width, height));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "Texture preview surface size changed: " + width + "x" + height);
        postRender(() -> renderer.resize(width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "Texture preview surface destroyed");
        postRender(renderer::release);
        stopRenderThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    @Override
    protected void onDetachedFromWindow() {
        clearFrame();
        super.onDetachedFromWindow();
    }

    private void postRender(Runnable runnable) {
        ensureRenderThread();
        renderHandler.post(runnable);
    }

    private void ensureRenderThread() {
        if (renderThread != null) {
            return;
        }
        renderThread = new HandlerThread("webstream-local-preview-gl");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
    }

    private void stopRenderThread() {
        HandlerThread thread = renderThread;
        renderThread = null;
        renderHandler = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private static final class GlRenderer {
        private static final int MODE_NONE = 0;
        private static final int MODE_BITMAP = 1;
        private static final int MODE_YUV = 2;

        private static final float[] VERTICES = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };

        private static final float[] TEX_COORDS = {
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}";

        private static final String BITMAP_FRAGMENT_SHADER =
                "precision mediump float;\n" +
                "uniform sampler2D uTexture;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}";

        private static final String YUV_FRAGMENT_SHADER =
                "precision mediump float;\n" +
                "uniform sampler2D uYTexture;\n" +
                "uniform sampler2D uUTexture;\n" +
                "uniform sampler2D uVTexture;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
                "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
                "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
                "    float r = y + 1.402 * v;\n" +
                "    float g = y - 0.344136 * u - 0.714136 * v;\n" +
                "    float b = y + 1.772 * u;\n" +
                "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
                "}";

        private final FloatBuffer vertexBuffer = createFloatBuffer(VERTICES);
        private final FloatBuffer texCoordBuffer = createFloatBuffer(TEX_COORDS);

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private Surface surface;

        private int bitmapProgram;
        private int yuvProgram;
        private int bitmapTextureId;
        private int yTextureId;
        private int uTextureId;
        private int vTextureId;
        private int viewWidth;
        private int viewHeight;
        private int bitmapTextureWidth;
        private int bitmapTextureHeight;
        private int yuvTextureWidth;
        private int yuvTextureHeight;
        private ByteBuffer yPlaneBuffer;
        private ByteBuffer uPlaneBuffer;
        private ByteBuffer vPlaneBuffer;
        private int frameMode;
        private int drawCount;

        void create(Surface surface, int width, int height) {
            release();
            this.surface = surface;
            viewWidth = width;
            viewHeight = height;

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] configAttributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            EGL14.eglChooseConfig(
                    eglDisplay,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.length,
                    configCount,
                    0);

            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(
                    eglDisplay,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0);
            eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    configs[0],
                    surface,
                    new int[]{EGL14.EGL_NONE},
                    0);
            makeCurrent();

            bitmapProgram = createProgram(VERTEX_SHADER, BITMAP_FRAGMENT_SHADER);
            yuvProgram = createProgram(VERTEX_SHADER, YUV_FRAGMENT_SHADER);
            bitmapTextureId = createTexture();
            yTextureId = createTexture();
            uTextureId = createTexture();
            vTextureId = createTexture();
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glViewport(0, 0, width, height);
            clear();
            Log.d(TAG, "Texture preview GL created. view=" + width + "x" + height
                    + ", bitmapProgram=" + bitmapProgram
                    + ", yuvProgram=" + yuvProgram);
        }

        void resize(int width, int height) {
            viewWidth = width;
            viewHeight = height;
            if (!isReady()) {
                return;
            }
            makeCurrent();
            GLES20.glViewport(0, 0, width, height);
            drawLastFrame();
        }

        void drawBitmap(Bitmap bitmap, int viewWidth, int viewHeight) {
            if (bitmap == null || bitmap.isRecycled() || !isReady()) {
                recycle(bitmap);
                return;
            }
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            makeCurrent();
            uploadBitmap(bitmap);
            frameMode = MODE_BITMAP;
            drawBitmapTexture(bitmap.getWidth(), bitmap.getHeight());
            recycle(bitmap);
        }

        void drawYuv(
                byte[] yuv420Data,
                int width,
                int height,
                CameraController.YuvFormat yuvFormat,
                int viewWidth,
                int viewHeight) {
            if (yuv420Data == null || !isReady()) {
                return;
            }
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            makeCurrent();
            uploadYuv(yuv420Data, width, height, yuvFormat);
            frameMode = MODE_YUV;
            drawYuvTextures(width, height);
        }

        void clear() {
            if (!isReady()) {
                return;
            }
            makeCurrent();
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            frameMode = MODE_NONE;
        }

        void release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
            if (surface != null) {
                surface.release();
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            surface = null;
            bitmapProgram = 0;
            yuvProgram = 0;
            bitmapTextureId = 0;
            yTextureId = 0;
            uTextureId = 0;
            vTextureId = 0;
            bitmapTextureWidth = 0;
            bitmapTextureHeight = 0;
            yuvTextureWidth = 0;
            yuvTextureHeight = 0;
            frameMode = MODE_NONE;
        }

        private boolean isReady() {
            return eglDisplay != EGL14.EGL_NO_DISPLAY
                    && eglContext != EGL14.EGL_NO_CONTEXT
                    && eglSurface != EGL14.EGL_NO_SURFACE;
        }

        private void makeCurrent() {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        private void uploadBitmap(Bitmap bitmap) {
            if (bitmapTextureWidth != bitmap.getWidth()
                    || bitmapTextureHeight != bitmap.getHeight()) {
                bitmapTextureWidth = bitmap.getWidth();
                bitmapTextureHeight = bitmap.getHeight();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            }
            checkGlError("uploadBitmap");
        }

        private void uploadYuv(
                byte[] yuv420Data,
                int width,
                int height,
                CameraController.YuvFormat yuvFormat) {
            int ySize = width * height;
            int chromaWidth = width / 2;
            int chromaHeight = height / 2;
            int chromaPlaneSize = chromaWidth * chromaHeight;
            if (width <= 0 || height <= 0 || yuv420Data.length < ySize + chromaPlaneSize * 2) {
                Log.d(TAG, "uploadYuv skipped: invalid YUV frame");
                return;
            }

            ByteBuffer y = ensurePlaneBuffer(yPlaneBuffer, ySize);
            ByteBuffer u = ensurePlaneBuffer(uPlaneBuffer, chromaPlaneSize);
            ByteBuffer v = ensurePlaneBuffer(vPlaneBuffer, chromaPlaneSize);
            yPlaneBuffer = y;
            uPlaneBuffer = u;
            vPlaneBuffer = v;

            y.clear();
            u.clear();
            v.clear();
            y.put(yuv420Data, 0, ySize).position(0);

            CameraController.YuvFormat format = yuvFormat == null
                    ? CameraController.YuvFormat.NV12
                    : yuvFormat;
            if (format == CameraController.YuvFormat.I420) {
                u.put(yuv420Data, ySize, chromaPlaneSize).position(0);
                v.put(yuv420Data, ySize + chromaPlaneSize, chromaPlaneSize).position(0);
            } else {
                boolean nv21 = format == CameraController.YuvFormat.NV21;
                for (int index = ySize; index + 1 < ySize + chromaPlaneSize * 2; index += 2) {
                    byte first = yuv420Data[index];
                    byte second = yuv420Data[index + 1];
                    if (nv21) {
                        v.put(first);
                        u.put(second);
                    } else {
                        u.put(first);
                        v.put(second);
                    }
                }
                u.position(0);
                v.position(0);
            }

            boolean recreate = width != yuvTextureWidth || height != yuvTextureHeight;
            yuvTextureWidth = width;
            yuvTextureHeight = height;
            uploadPlane(yTextureId, GLES20.GL_TEXTURE0, width, height, y, recreate);
            uploadPlane(uTextureId, GLES20.GL_TEXTURE1, chromaWidth, chromaHeight, u, recreate);
            uploadPlane(vTextureId, GLES20.GL_TEXTURE2, chromaWidth, chromaHeight, v, recreate);
            checkGlError("uploadYuv");
        }

        private ByteBuffer ensurePlaneBuffer(ByteBuffer buffer, int capacity) {
            if (buffer == null || buffer.capacity() < capacity) {
                return ByteBuffer.allocateDirect(capacity);
            }
            return buffer;
        }

        private void uploadPlane(
                int textureId,
                int textureUnit,
                int width,
                int height,
                ByteBuffer plane,
                boolean recreate) {
            plane.position(0);
            GLES20.glActiveTexture(textureUnit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            if (recreate) {
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_LUMINANCE,
                        width,
                        height,
                        0,
                        GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE,
                        plane);
            } else {
                GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        width,
                        height,
                        GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE,
                        plane);
            }
        }

        private void drawBitmapTexture(int frameWidth, int frameHeight) {
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(bitmapProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(bitmapProgram, "uTexture"), 0);
            drawQuad(bitmapProgram, frameWidth, frameHeight);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            logDraw("bitmap", frameWidth, frameHeight);
        }

        private void drawYuvTextures(int frameWidth, int frameHeight) {
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(yuvProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(yuvProgram, "uYTexture"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(yuvProgram, "uUTexture"), 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(yuvProgram, "uVTexture"), 2);
            drawQuad(yuvProgram, frameWidth, frameHeight);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            logDraw("yuv", frameWidth, frameHeight);
        }

        private void drawLastFrame() {
            if (frameMode == MODE_BITMAP && bitmapTextureWidth > 0) {
                drawBitmapTexture(bitmapTextureWidth, bitmapTextureHeight);
            } else if (frameMode == MODE_YUV && yuvTextureWidth > 0) {
                drawYuvTextures(yuvTextureWidth, yuvTextureHeight);
            }
        }

        private void drawQuad(int program, int frameWidth, int frameHeight) {
            updateCenterCropTexCoords(frameWidth, frameHeight);
            int positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(
                    positionLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer);

            texCoordBuffer.position(0);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glVertexAttribPointer(
                    texCoordLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLocation);
            GLES20.glDisableVertexAttribArray(texCoordLocation);
        }

        private void updateCenterCropTexCoords(int frameWidth, int frameHeight) {
            float left = 0f;
            float right = 1f;
            float top = 0f;
            float bottom = 1f;
            if (frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                float frameAspect = frameWidth / (float) frameHeight;
                float viewAspect = viewWidth / (float) viewHeight;
                if (frameAspect > viewAspect) {
                    float visibleWidth = viewAspect / frameAspect;
                    left = (1f - visibleWidth) / 2f;
                    right = 1f - left;
                } else if (frameAspect < viewAspect) {
                    float visibleHeight = frameAspect / viewAspect;
                    top = (1f - visibleHeight) / 2f;
                    bottom = 1f - top;
                }
            }

            float[] coords = {
                    left, bottom,
                    right, bottom,
                    left, top,
                    right, top
            };
            texCoordBuffer.position(0);
            texCoordBuffer.put(coords).position(0);
        }

        private int createTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int id = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            return id;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int programId = GLES20.glCreateProgram();
            GLES20.glAttachShader(programId, vertexShader);
            GLES20.glAttachShader(programId, fragmentShader);
            GLES20.glLinkProgram(programId);

            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                String error = GLES20.glGetProgramInfoLog(programId);
                GLES20.glDeleteProgram(programId);
                throw new RuntimeException("OpenGL program link failed: " + error);
            }

            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return programId;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);

            int[] compileStatus = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
            if (compileStatus[0] == 0) {
                String error = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("OpenGL shader compile failed: " + error);
            }
            return shader;
        }

        private void checkGlError(String label) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.d(TAG, label + ": glError " + error);
            }
        }

        private void logDraw(String mode, int frameWidth, int frameHeight) {
            drawCount += 1;
            if (shouldLog(drawCount)) {
                Log.d(TAG, "Texture preview draw: count=" + drawCount
                        + ", mode=" + mode
                        + ", frame=" + frameWidth + "x" + frameHeight
                        + ", view=" + viewWidth + "x" + viewHeight);
            }
        }

        private void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }

        private static FloatBuffer createFloatBuffer(float[] values) {
            FloatBuffer buffer = ByteBuffer
                    .allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            buffer.put(values).position(0);
            return buffer;
        }
    }

    private static boolean shouldLog(int count) {
        return count <= 10 || count % 30 == 0;
    }

    private static String describeBitmapSamples(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return "unavailable";
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        return "center=" + describeColor(bitmap.getPixel(width / 2, height / 2))
                + ", q1=" + describeColor(bitmap.getPixel(width / 4, height / 4))
                + ", q3=" + describeColor(bitmap.getPixel(width * 3 / 4, height * 3 / 4));
    }

    private static String describeColor(int color) {
        return "a" + ((color >>> 24) & 0xFF)
                + "/r" + ((color >>> 16) & 0xFF)
                + "/g" + ((color >>> 8) & 0xFF)
                + "/b" + (color & 0xFF);
    }
}
