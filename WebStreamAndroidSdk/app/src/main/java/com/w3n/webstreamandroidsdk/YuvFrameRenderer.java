package com.w3n.webstreamandroidsdk;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import com.w3n.webstream.Util.H264FrameBatchDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class YuvFrameRenderer implements GLSurfaceView.Renderer {

    private final int frameWidth;
    private final int frameHeight;

    private int program;

    private int yTextureId;
    private int uTextureId;
    private int vTextureId;

    private int aPositionLocation;
    private int aTexCoordLocation;

    private int uYTextureLocation;
    private int uUTextureLocation;
    private int uVTextureLocation;

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texCoordBuffer;

    private final Object frameLock = new Object();

    private YuvPlanes pendingFrame;
    private YuvPlanes lastFrame;

    private boolean texturesCreated = false;

    /**
     * Set this true if colors look wrong / purple-green.
     *
     * Some decoder byte-buffer outputs are effectively NV21-style VU instead of UV.
     */
    private boolean uvSwapped = false;

    private static final float[] VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    /**
     * If your image is upside down, swap this with:
     *
     * 0f, 0f,
     * 1f, 0f,
     * 0f, 1f,
     * 1f, 1f
     */
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

    private static final String FRAGMENT_SHADER =
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

    public YuvFrameRenderer(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;

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

        YuvPlanes planes = extractYuvPlanes(
                frame.buffer,
                frame.mediaFormat,
                frame.width,
                frame.height,
                uvSwapped
        );

        synchronized (frameLock) {
            pendingFrame = planes;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");

        uYTextureLocation = GLES20.glGetUniformLocation(program, "uYTexture");
        uUTextureLocation = GLES20.glGetUniformLocation(program, "uUTexture");
        uVTextureLocation = GLES20.glGetUniformLocation(program, "uVTexture");

        yTextureId = createTexture();
        uTextureId = createTexture();
        vTextureId = createTexture();

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        YuvPlanes frameToRender = null;

        synchronized (frameLock) {
            if (pendingFrame != null) {
                frameToRender = pendingFrame;
                pendingFrame = null;
            }
        }

        if (frameToRender != null) {
            lastFrame = frameToRender;
            uploadYuvFrame(frameToRender);
        }

        if (lastFrame != null) {
            drawTextures();
        }
    }

    private void uploadYuvFrame(YuvPlanes planes) {
        uploadPlane(
                yTextureId,
                GLES20.GL_TEXTURE0,
                frameWidth,
                frameHeight,
                planes.y
        );

        uploadPlane(
                uTextureId,
                GLES20.GL_TEXTURE1,
                frameWidth / 2,
                frameHeight / 2,
                planes.u
        );

        uploadPlane(
                vTextureId,
                GLES20.GL_TEXTURE2,
                frameWidth / 2,
                frameHeight / 2,
                planes.v
        );

        texturesCreated = true;

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

    private void drawTextures() {
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
        GLES20.glUniform1i(uYTextureLocation, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId);
        GLES20.glUniform1i(uUTextureLocation, 1);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId);
        GLES20.glUniform1i(uVTextureLocation, 2);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(
                aPositionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
        );

        texCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(
                aTexCoordLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                texCoordBuffer
        );

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);

        checkGlError("drawTextures");
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

    private static YuvPlanes extractYuvPlanes(
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

        return new YuvPlanes(yPlane, uPlane, vPlane);
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

    private static final class YuvPlanes {
        final ByteBuffer y;
        final ByteBuffer u;
        final ByteBuffer v;

        YuvPlanes(
                ByteBuffer y,
                ByteBuffer u,
                ByteBuffer v
        ) {
            this.y = y;
            this.u = u;
            this.v = v;
        }
    }
}