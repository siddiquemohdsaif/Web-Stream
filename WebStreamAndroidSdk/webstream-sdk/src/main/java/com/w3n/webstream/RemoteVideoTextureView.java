package com.w3n.webstream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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

import com.w3n.webstream.Util.H264FrameBatchDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class RemoteVideoTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "RemoteVideoTextureView";

    private final GlRenderer renderer = new GlRenderer();
    private HandlerThread renderThread;
    private Handler renderHandler;

    RemoteVideoTextureView(Context context, int rotationDegrees) {
        super(context);
        renderer.setRotationDegrees(rotationDegrees);
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    void renderDecodedFrame(H264FrameBatchDecoder.DecodedFrame frame) {
        if (frame == null || frame.buffer == null) {
            releaseFrame(frame);
            return;
        }
        postRender(() -> renderer.drawDecodedFrame(frame, getWidth(), getHeight()));
    }

    void renderBitmap(Bitmap bitmap, int rotationDegrees) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        postRender(() -> renderer.drawBitmap(bitmap, rotationDegrees, getWidth(), getHeight()));
    }

    void clearFrame() {
        if (renderHandler != null) {
            postRender(renderer::clear);
        }
    }

    void releaseRenderer() {
        if (renderHandler != null) {
            postRender(renderer::release);
        }
        stopRenderThread();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        ensureRenderThread();
        Surface surface = new Surface(surfaceTexture);
        postRender(() -> renderer.create(surface, width, height));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        postRender(() -> renderer.resize(width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releaseRenderer();
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
        renderThread = new HandlerThread("webstream-remote-texture-gl");
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

    private static void releaseFrame(H264FrameBatchDecoder.DecodedFrame frame) {
        if (frame != null) {
            frame.release();
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
        private int viewWidth = 1;
        private int viewHeight = 1;
        private int bitmapTextureWidth;
        private int bitmapTextureHeight;
        private int bitmapFrameWidth;
        private int bitmapFrameHeight;
        private int yuvTextureWidth;
        private int yuvTextureHeight;
        private ByteBuffer yPlaneBuffer;
        private ByteBuffer uPlaneBuffer;
        private ByteBuffer vPlaneBuffer;
        private int frameMode;
        private int rotationDegrees;

        void setRotationDegrees(int rotationDegrees) {
            this.rotationDegrees = normalizeRotationDegrees(rotationDegrees);
        }

        void create(Surface surface, int width, int height) {
            release();
            this.surface = surface;
            viewWidth = Math.max(1, width);
            viewHeight = Math.max(1, height);

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
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            clear();
        }

        void resize(int width, int height) {
            viewWidth = Math.max(1, width);
            viewHeight = Math.max(1, height);
            if (!isReady()) {
                return;
            }
            makeCurrent();
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            drawLastFrame();
        }

        void drawDecodedFrame(
                H264FrameBatchDecoder.DecodedFrame frame,
                int viewWidth,
                int viewHeight) {
            if (frame == null || frame.buffer == null || !isReady()) {
                releaseFrame(frame);
                return;
            }
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            try {
                YuvPlanes planes = extractYuvPlanes(frame);
                makeCurrent();
                uploadYuv(planes);
                frameMode = MODE_YUV;
                drawYuvTextures(planes.width, planes.height);
            } finally {
                frame.release();
            }
        }

        void drawBitmap(Bitmap bitmap, int rotationDegrees, int viewWidth, int viewHeight) {
            if (bitmap == null || bitmap.isRecycled() || !isReady()) {
                recycle(bitmap);
                return;
            }
            this.rotationDegrees = normalizeRotationDegrees(rotationDegrees);
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            makeCurrent();
            uploadBitmap(bitmap);
            bitmapFrameWidth = bitmap.getWidth();
            bitmapFrameHeight = bitmap.getHeight();
            frameMode = MODE_BITMAP;
            drawBitmapTexture(bitmapFrameWidth, bitmapFrameHeight);
            recycle(bitmap);
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
            bitmapFrameWidth = 0;
            bitmapFrameHeight = 0;
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
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
            if (bitmapTextureWidth != bitmap.getWidth()
                    || bitmapTextureHeight != bitmap.getHeight()) {
                bitmapTextureWidth = bitmap.getWidth();
                bitmapTextureHeight = bitmap.getHeight();
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            }
            checkGlError("uploadBitmap");
        }

        private void uploadYuv(YuvPlanes planes) {
            boolean recreate = planes.width != yuvTextureWidth || planes.height != yuvTextureHeight;
            yuvTextureWidth = planes.width;
            yuvTextureHeight = planes.height;
            uploadPlane(yTextureId, GLES20.GL_TEXTURE0, planes.width, planes.height, planes.y, recreate);
            uploadPlane(uTextureId, GLES20.GL_TEXTURE1, planes.width / 2, planes.height / 2, planes.u, recreate);
            uploadPlane(vTextureId, GLES20.GL_TEXTURE2, planes.width / 2, planes.height / 2, planes.v, recreate);
            checkGlError("uploadYuv");
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
        }

        private void drawLastFrame() {
            if (frameMode == MODE_BITMAP && bitmapFrameWidth > 0 && bitmapFrameHeight > 0) {
                drawBitmapTexture(bitmapFrameWidth, bitmapFrameHeight);
            } else if (frameMode == MODE_YUV && yuvTextureWidth > 0 && yuvTextureHeight > 0) {
                drawYuvTextures(yuvTextureWidth, yuvTextureHeight);
            }
        }

        private void drawQuad(int program, int frameWidth, int frameHeight) {
            updateCenterCropVertices(frameWidth, frameHeight);
            updateTexCoords();
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

        private void updateCenterCropVertices(int frameWidth, int frameHeight) {
            float xScale = 1f;
            float yScale = 1f;
            if (frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                boolean swapsDimensions = rotationDegrees == 90 || rotationDegrees == 270;
                int rotatedFrameWidth = swapsDimensions ? frameHeight : frameWidth;
                int rotatedFrameHeight = swapsDimensions ? frameWidth : frameHeight;
                float frameAspect = rotatedFrameWidth / (float) rotatedFrameHeight;
                float viewAspect = viewWidth / (float) viewHeight;
                if (frameAspect > viewAspect) {
                    xScale = frameAspect / viewAspect;
                } else if (frameAspect < viewAspect) {
                    yScale = viewAspect / frameAspect;
                }
            }

            float[] vertices = {
                    -xScale, -yScale,
                     xScale, -yScale,
                    -xScale,  yScale,
                     xScale,  yScale
            };
            vertexBuffer.position(0);
            vertexBuffer.put(vertices).position(0);
        }

        private void updateTexCoords() {
            texCoordBuffer.position(0);
            texCoordBuffer.put(createRotatedTexCoords(0f, 1f, 0f, 1f)).position(0);
        }

        private float[] createRotatedTexCoords(float left, float right, float top, float bottom) {
            switch (rotationDegrees) {
                case 90:
                    return new float[]{
                            right, bottom,
                            right, top,
                            left, bottom,
                            left, top
                    };
                case 180:
                    return new float[]{
                            right, top,
                            left, top,
                            right, bottom,
                            left, bottom
                    };
                case 270:
                    return new float[]{
                            left, top,
                            left, bottom,
                            right, top,
                            right, bottom
                    };
                case 0:
                default:
                    return new float[]{
                            left, bottom,
                            right, bottom,
                            left, top,
                            right, top
                    };
            }
        }

        private YuvPlanes extractYuvPlanes(H264FrameBatchDecoder.DecodedFrame frame) {
            ByteBuffer buffer = frame.buffer.duplicate();
            buffer.position(frame.offset);
            buffer.limit(frame.offset + frame.size);

            int width = frame.width;
            int height = frame.height;
            MediaFormat mediaFormat = frame.mediaFormat;
            int stride = getFormatInteger(mediaFormat, "stride", width);
            int sliceHeight = getFormatInteger(mediaFormat, "slice-height", height);
            int colorFormat = getFormatInteger(
                    mediaFormat,
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            byte[] raw = new byte[buffer.remaining()];
            buffer.get(raw);

            ByteBuffer yPlane = ensurePlaneBuffer(yPlaneBuffer, width * height);
            ByteBuffer uPlane = ensurePlaneBuffer(uPlaneBuffer, (width / 2) * (height / 2));
            ByteBuffer vPlane = ensurePlaneBuffer(vPlaneBuffer, (width / 2) * (height / 2));
            yPlaneBuffer = yPlane;
            uPlaneBuffer = uPlane;
            vPlaneBuffer = vPlane;

            yPlane.clear();
            uPlane.clear();
            vPlane.clear();
            copyYPlane(raw, yPlane, width, height, stride);

            boolean planar =
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                            || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
                            || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible; // now flexible we treat as planar
            int yPlaneSize = stride * sliceHeight;
            if (planar) {
                copyPlanarChroma(raw, uPlane, vPlane, width, height, stride, yPlaneSize);
            } else {
                copySemiPlanarChroma(raw, uPlane, vPlane, width, height, stride, yPlaneSize);
            }

            yPlane.position(0);
            uPlane.position(0);
            vPlane.position(0);
            return new YuvPlanes(width, height, yPlane, uPlane, vPlane);
        }

        private ByteBuffer ensurePlaneBuffer(ByteBuffer buffer, int capacity) {
            if (buffer == null || buffer.capacity() < capacity) {
                return ByteBuffer.allocateDirect(capacity);
            }
            return buffer;
        }

        private static void copyYPlane(byte[] raw, ByteBuffer yPlane, int width, int height, int stride) {
            for (int row = 0; row < height; row++) {
                int inputOffset = row * stride;
                if (inputOffset + width > raw.length) {
                    break;
                }
                yPlane.put(raw, inputOffset, width);
            }
            yPlane.position(0);
        }

        private static void copySemiPlanarChroma(
                byte[] raw,
                ByteBuffer uPlane,
                ByteBuffer vPlane,
                int width,
                int height,
                int stride,
                int yPlaneSize) {
            int chromaHeight = height / 2;
            int chromaWidth = width / 2;
            for (int row = 0; row < chromaHeight; row++) {
                int rowStart = yPlaneSize + row * stride;
                for (int col = 0; col < chromaWidth; col++) {
                    int input = rowStart + col * 2;
                    if (input + 1 >= raw.length) {
                        return;
                    }
                    uPlane.put(raw[input]);
                    vPlane.put(raw[input + 1]);
                }
            }
            uPlane.position(0);
            vPlane.position(0);
        }

        private static void copyPlanarChroma(
                byte[] raw,
                ByteBuffer uPlane,
                ByteBuffer vPlane,
                int width,
                int height,
                int stride,
                int yPlaneSize) {
            int chromaWidth = width / 2;
            int chromaHeight = height / 2;
            int chromaStride = Math.max(1, stride / 2);
            int chromaPlaneSize = chromaStride * chromaHeight;
            int firstChromaPlaneStart = yPlaneSize;
            int secondChromaPlaneStart = yPlaneSize + chromaPlaneSize;

            for (int row = 0; row < chromaHeight; row++) {
                int firstRowStart = firstChromaPlaneStart + row * chromaStride;
                int secondRowStart = secondChromaPlaneStart + row * chromaStride;
                for (int col = 0; col < chromaWidth; col++) {
                    int firstIndex = firstRowStart + col;
                    int secondIndex = secondRowStart + col;
                    if (firstIndex >= raw.length || secondIndex >= raw.length) {
                        return;
                    }
                    uPlane.put(raw[firstIndex]);
                    vPlane.put(raw[secondIndex]);
                }
            }
            uPlane.position(0);
            vPlane.position(0);
        }

        private int createTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int id = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
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

        private void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }

        private static int getFormatInteger(MediaFormat format, String key, int defaultValue) {
            if (format == null || !format.containsKey(key)) {
                return defaultValue;
            }
            try {
                return format.getInteger(key);
            } catch (RuntimeException ignored) {
                return defaultValue;
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

        private static int normalizeRotationDegrees(int rotationDegrees) {
            int normalized = ((rotationDegrees % 360) + 360) % 360;
            if (normalized % 90 != 0) {
                throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees.");
            }
            return normalized;
        }
    }

    private static final class YuvPlanes {
        final int width;
        final int height;
        final ByteBuffer y;
        final ByteBuffer u;
        final ByteBuffer v;

        YuvPlanes(int width, int height, ByteBuffer y, ByteBuffer u, ByteBuffer v) {
            this.width = width;
            this.height = height;
            this.y = y;
            this.u = u;
            this.v = v;
        }
    }
}
