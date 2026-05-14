package com.w3n.webstream;

import android.graphics.Bitmap;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.w3n.webstream.Util.H264FrameBatchDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class YuvFrameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "YuvFrameRenderer";
    private static final int MODE_NONE = 0;
    private static final int MODE_YUV = 1;
    private static final int MODE_BITMAP = 2;

    private int yuvProgram;
    private int bitmapProgram;

    private int yTextureId;
    private int uTextureId;
    private int vTextureId;
    private int bitmapTextureId;

    private int yuvPositionLocation;
    private int yuvTexCoordLocation;
    private int bitmapPositionLocation;
    private int bitmapTexCoordLocation;

    private int uYTextureLocation;
    private int uUTextureLocation;
    private int uVTextureLocation;
    private int uBitmapTextureLocation;

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texCoordBuffer;
    private final Object frameLock = new Object();

    private YuvPlanes pendingFrame;
    private YuvPlanes lastFrame;
    private Bitmap pendingBitmap;

    private boolean texturesCreated;
    private int textureFrameWidth;
    private int textureFrameHeight;
    private int bitmapTextureWidth;
    private int bitmapTextureHeight;
    private int bitmapFrameWidth;
    private int bitmapFrameHeight;
    private int bitmapRotationDegrees;
    private int yuvRotationDegrees;
    private int frameMode = MODE_NONE;
    private int surfaceWidth = 1;
    private int surfaceHeight = 1;
    private boolean loggedFirstFrameStats;
    private boolean loggedFirstDraw;

    /**
     * Set this true if colors look wrong / purple-green.
     *
     * Some decoder byte-buffer outputs are effectively NV21-style VU instead of UV.
     */
    private boolean uvSwapped;

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

    private static final String YUV_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uYTexture;\n" +
            "uniform sampler2D uUTexture;\n" +
            "uniform sampler2D uVTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "\n" +
            "void main() {\n" +
            "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
            "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
            "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
            "\n" +
            "    float r = y + 1.402 * v;\n" +
            "    float g = y - 0.344136 * u - 0.714136 * v;\n" +
            "    float b = y + 1.772 * u;\n" +
            "\n" +
            "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}";

    private static final String BITMAP_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}";

    public YuvFrameRenderer(int frameWidth, int frameHeight) {
        vertexBuffer = ByteBuffer
                .allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texCoordBuffer = ByteBuffer
                .allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).position(0);
    }

    public void setUvSwapped(boolean uvSwapped) {
        this.uvSwapped = uvSwapped;
    }

    /**
     * Call this from your decoder callback:
     *
     * renderer.updateFrame(frame);
     * glSurfaceView.requestRender();
     */
    public void updateFrame(H264FrameBatchDecoder.DecodedFrame frame) {
        if (frame == null || frame.buffer == null) {
            return;
        }

        YuvPlanes planes;
        try {
            planes = extractYuvPlanes(
                    frame.buffer,
                    frame.mediaFormat,
                    frame.width,
                    frame.height,
                    uvSwapped
            );
        } finally {
            frame.release();
        }

        synchronized (frameLock) {
            if (pendingBitmap != null && !pendingBitmap.isRecycled()) {
                pendingBitmap.recycle();
                pendingBitmap = null;
            }
            pendingFrame = planes;
        }
    }

    public void updateBitmapFrame(Bitmap bitmap, int rotationDegrees) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        synchronized (frameLock) {
            if (pendingBitmap != null && pendingBitmap != bitmap && !pendingBitmap.isRecycled()) {
                pendingBitmap.recycle();
            }
            pendingBitmap = bitmap;
            pendingFrame = null;
            bitmapRotationDegrees = normalizeRotationDegrees(rotationDegrees);
        }
    }

    public void setYuvRotationDegrees(int rotationDegrees) {
        yuvRotationDegrees = normalizeRotationDegrees(rotationDegrees);
    }

    public void releasePendingBitmap() {
        synchronized (frameLock) {
            if (pendingBitmap != null && !pendingBitmap.isRecycled()) {
                pendingBitmap.recycle();
            }
            pendingBitmap = null;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        yuvProgram = createProgram(VERTEX_SHADER, YUV_FRAGMENT_SHADER);
        bitmapProgram = createProgram(VERTEX_SHADER, BITMAP_FRAGMENT_SHADER);

        yuvPositionLocation = GLES20.glGetAttribLocation(yuvProgram, "aPosition");
        yuvTexCoordLocation = GLES20.glGetAttribLocation(yuvProgram, "aTexCoord");
        bitmapPositionLocation = GLES20.glGetAttribLocation(bitmapProgram, "aPosition");
        bitmapTexCoordLocation = GLES20.glGetAttribLocation(bitmapProgram, "aTexCoord");

        uYTextureLocation = GLES20.glGetUniformLocation(yuvProgram, "uYTexture");
        uUTextureLocation = GLES20.glGetUniformLocation(yuvProgram, "uUTexture");
        uVTextureLocation = GLES20.glGetUniformLocation(yuvProgram, "uVTexture");
        uBitmapTextureLocation = GLES20.glGetUniformLocation(bitmapProgram, "uTexture");

        yTextureId = createTexture();
        uTextureId = createTexture();
        vTextureId = createTexture();
        bitmapTextureId = createTexture();

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        Log.d("DECODER_PARVEZ", "GL surface created.");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, width, height);
        Log.d("DECODER_PARVEZ", "GL surface changed. width=" + width + ", height=" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        YuvPlanes frameToRender = null;
        Bitmap bitmapToRender = null;
        int bitmapRotation = 0;

        synchronized (frameLock) {
            if (pendingBitmap != null) {
                bitmapToRender = pendingBitmap;
                bitmapRotation = bitmapRotationDegrees;
                pendingBitmap = null;
            } else if (pendingFrame != null) {
                frameToRender = pendingFrame;
                pendingFrame = null;
            }
        }

        if (bitmapToRender != null) {
            uploadBitmapFrame(bitmapToRender, bitmapRotation);
            bitmapToRender.recycle();
        } else if (frameToRender != null) {
            lastFrame = frameToRender;
            uploadYuvFrame(frameToRender);
            if (!loggedFirstDraw) {
                loggedFirstDraw = true;
                Log.d("DECODER_PARVEZ", "First decoded frame uploaded to GL. width="
                        + frameToRender.width + ", height=" + frameToRender.height);
            }
        }

        if (frameMode == MODE_BITMAP) {
            drawBitmapTexture();
        } else if (frameMode == MODE_YUV && lastFrame != null) {
            drawYuvTextures();
        }
    }

    private void uploadBitmapFrame(Bitmap bitmap, int rotationDegrees) {
        bitmapFrameWidth = bitmap.getWidth();
        bitmapFrameHeight = bitmap.getHeight();
        bitmapRotationDegrees = rotationDegrees;

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
        if (bitmapTextureWidth != bitmapFrameWidth || bitmapTextureHeight != bitmapFrameHeight) {
            bitmapTextureWidth = bitmapFrameWidth;
            bitmapTextureHeight = bitmapFrameHeight;
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }

        frameMode = MODE_BITMAP;
        checkGlError("uploadBitmapFrame");
    }

    private void uploadYuvFrame(YuvPlanes planes) {
        if (planes.width != textureFrameWidth || planes.height != textureFrameHeight) {
            texturesCreated = false;
            textureFrameWidth = planes.width;
            textureFrameHeight = planes.height;
        }

        uploadPlane(
                yTextureId,
                GLES20.GL_TEXTURE0,
                planes.width,
                planes.height,
                planes.y
        );

        uploadPlane(
                uTextureId,
                GLES20.GL_TEXTURE1,
                planes.width / 2,
                planes.height / 2,
                planes.u
        );

        uploadPlane(
                vTextureId,
                GLES20.GL_TEXTURE2,
                planes.width / 2,
                planes.height / 2,
                planes.v
        );

        texturesCreated = true;
        frameMode = MODE_YUV;

        checkGlError("uploadYuvFrame");
    }

    private void uploadPlane(
            int textureId,
            int textureUnit,
            int width,
            int height,
            ByteBuffer plane
    ) {
        plane.position(0);

        GLES20.glActiveTexture(textureUnit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (!texturesCreated) {
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    plane
            );
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
                    plane
            );
        }
    }

    private void drawYuvTextures() {
        updateCenterCropVertices(textureFrameWidth, textureFrameHeight, yuvRotationDegrees);
        updateTexCoords(yuvRotationDegrees);
        GLES20.glUseProgram(yuvProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
        GLES20.glUniform1i(uYTextureLocation, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId);
        GLES20.glUniform1i(uUTextureLocation, 1);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId);
        GLES20.glUniform1i(uVTextureLocation, 2);

        drawQuad(yuvPositionLocation, yuvTexCoordLocation);
        checkGlError("drawYuvTextures");
    }

    private void drawBitmapTexture() {
        updateCenterCropVertices(bitmapFrameWidth, bitmapFrameHeight, bitmapRotationDegrees);
        updateTexCoords(bitmapRotationDegrees);
        GLES20.glUseProgram(bitmapProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
        GLES20.glUniform1i(uBitmapTextureLocation, 0);

        drawQuad(bitmapPositionLocation, bitmapTexCoordLocation);
        checkGlError("drawBitmapTexture");
    }

    private void drawQuad(int positionLocation, int texCoordLocation) {
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
        );

        texCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(texCoordLocation);
        GLES20.glVertexAttribPointer(
                texCoordLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                texCoordBuffer
        );

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(positionLocation);
        GLES20.glDisableVertexAttribArray(texCoordLocation);
    }

    private void updateCenterCropVertices(
            int frameWidth,
            int frameHeight,
            int rotationDegrees
    ) {
        float xScale = 1f;
        float yScale = 1f;
        if (frameWidth > 0 && frameHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
            boolean swapsDimensions = rotationDegrees == 90 || rotationDegrees == 270;
            int rotatedFrameWidth = swapsDimensions ? frameHeight : frameWidth;
            int rotatedFrameHeight = swapsDimensions ? frameWidth : frameHeight;
            float frameAspect = rotatedFrameWidth / (float) rotatedFrameHeight;
            float viewAspect = surfaceWidth / (float) surfaceHeight;
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

    private void updateTexCoords(int rotationDegrees) {
        texCoordBuffer.position(0);
        texCoordBuffer.put(createRotatedTexCoords(0f, 1f, 0f, 1f, rotationDegrees))
                .position(0);
    }

    private float[] createRotatedTexCoords(
            float left,
            float right,
            float top,
            float bottom,
            int rotationDegrees
    ) {
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

    private int createTexture() {
        int[] textures = new int[1];

        GLES20.glGenTextures(1, textures, 0);

        int id = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        );

        return id;
    }

    private YuvPlanes extractYuvPlanes(
            ByteBuffer codecBuffer,
            MediaFormat mediaFormat,
            int width,
            int height,
            boolean uvSwapped
    ) {
        ByteBuffer buffer = codecBuffer.duplicate();
        buffer.position(0);

        int stride = getFormatInteger(mediaFormat, "stride", width);
        int sliceHeight = getFormatInteger(mediaFormat, "slice-height", height);

        int colorFormat = getFormatInteger(
                mediaFormat,
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        );

        byte[] raw = new byte[buffer.remaining()];
        buffer.get(raw);

        if (!loggedFirstFrameStats) {
            loggedFirstFrameStats = true;
            logFirstFrameStats(raw, width, height, stride);
        }

        ByteBuffer yPlane = ByteBuffer.allocateDirect(width * height);
        ByteBuffer uPlane = ByteBuffer.allocateDirect((width / 2) * (height / 2));
        ByteBuffer vPlane = ByteBuffer.allocateDirect((width / 2) * (height / 2));

        copyYPlane(raw, yPlane, width, height, stride);

        boolean planar =
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;

        int yPlaneSize = stride * sliceHeight;

        if (planar) {
            copyPlanarChroma(
                    raw,
                    uPlane,
                    vPlane,
                    width,
                    height,
                    stride,
                    yPlaneSize,
                    uvSwapped
            );
        } else {
            copySemiPlanarChroma(
                    raw,
                    uPlane,
                    vPlane,
                    width,
                    height,
                    stride,
                    yPlaneSize,
                    uvSwapped
            );
        }

        yPlane.position(0);
        uPlane.position(0);
        vPlane.position(0);

        return new YuvPlanes(width, height, yPlane, uPlane, vPlane);
    }

    private static void logFirstFrameStats(
            byte[] raw,
            int width,
            int height,
            int stride
    ) {
        int sampleRows = Math.min(height, 24);
        int sampleCols = Math.min(width, 64);
        int min = 255;
        int max = 0;
        long sum = 0L;
        int count = 0;

        for (int row = 0; row < sampleRows; row++) {
            int rowStart = row * stride;
            if (rowStart >= raw.length) {
                break;
            }

            for (int col = 0; col < sampleCols && rowStart + col < raw.length; col++) {
                int value = raw[rowStart + col] & 0xff;
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value;
                count++;
            }
        }

        if (count == 0) {
            Log.d("DECODER_PARVEZ", "Decoded Y sample unavailable. rawBytes=" + raw.length);
            return;
        }

        Log.d("DECODER_PARVEZ", "Decoded Y sample. min=" + min
                + ", max=" + max
                + ", avg=" + (sum / count)
                + ", rawBytes=" + raw.length
                + ", width=" + width
                + ", height=" + height
                + ", stride=" + stride);
    }

    private static void copyYPlane(
            byte[] raw,
            ByteBuffer yPlane,
            int width,
            int height,
            int stride
    ) {
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
            int yPlaneSize,
            boolean uvSwapped
    ) {
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;

        for (int row = 0; row < chromaHeight; row++) {
            int rowStart = yPlaneSize + row * stride;

            for (int col = 0; col < chromaWidth; col++) {
                int input = rowStart + col * 2;

                if (input + 1 >= raw.length) {
                    return;
                }

                byte first = raw[input];
                byte second = raw[input + 1];

                if (uvSwapped) {
                    vPlane.put(first);
                    uPlane.put(second);
                } else {
                    uPlane.put(first);
                    vPlane.put(second);
                }
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
            int yPlaneSize,
            boolean uvSwapped
    ) {
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

                byte first = raw[firstIndex];
                byte second = raw[secondIndex];

                if (uvSwapped) {
                    vPlane.put(first);
                    uPlane.put(second);
                } else {
                    uPlane.put(first);
                    vPlane.put(second);
                }
            }
        }

        uPlane.position(0);
        vPlane.position(0);
    }

    private static int getFormatInteger(
            MediaFormat format,
            String key,
            int defaultValue
    ) {
        if (format == null || !format.containsKey(key)) {
            return defaultValue;
        }

        try {
            return format.getInteger(key);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
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
            throw new RuntimeException(label + ": glError " + error);
        }
    }

    private static int normalizeRotationDegrees(int rotationDegrees) {
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException(
                    "Rotation must be 0, 90, 180, or 270 degrees.");
        }
        return normalized;
    }

    private static final class YuvPlanes {
        final int width;
        final int height;
        final ByteBuffer y;
        final ByteBuffer u;
        final ByteBuffer v;

        YuvPlanes(
                int width,
                int height,
                ByteBuffer y,
                ByteBuffer u,
                ByteBuffer v
        ) {
            this.width = width;
            this.height = height;
            this.y = y;
            this.u = u;
            this.v = v;
        }
    }
}
