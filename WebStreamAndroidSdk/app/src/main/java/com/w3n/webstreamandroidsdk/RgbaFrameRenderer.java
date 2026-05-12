package com.w3n.webstreamandroidsdk;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class RgbaFrameRenderer implements GLSurfaceView.Renderer {

    private final int frameWidth;
    private final int frameHeight;

    private int program;
    private int textureId;

    private int aPositionLocation;
    private int aTexCoordLocation;
    private int uTextureLocation;

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texCoordBuffer;

    private final Object frameLock = new Object();
    private ByteBuffer pendingFrame;

    private boolean textureCreated = false;

    private static final float[] VERTICES = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    // This may need vertical flip depending on your source frame orientation.
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
                    "uniform sampler2D uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    public RgbaFrameRenderer(int frameWidth, int frameHeight) {
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

    public void updateFrame(ByteBuffer rgbaBuffer) {
        if (rgbaBuffer == null) {
            return;
        }

        int expectedSize = frameWidth * frameHeight * 4;

        rgbaBuffer.rewind();

        if (rgbaBuffer.remaining() < expectedSize) {
            throw new IllegalArgumentException(
                    "RGBA buffer too small. Expected at least "
                            + expectedSize
                            + " bytes, got "
                            + rgbaBuffer.remaining()
            );
        }

        // Important:
        // Make a direct copy because OpenGL rendering happens on GLSurfaceView's GL thread.
        ByteBuffer copy = ByteBuffer.allocateDirect(expectedSize);
        copy.put(rgbaBuffer);
        copy.position(0);

        synchronized (frameLock) {
            pendingFrame = copy;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTextureLocation = GLES20.glGetUniformLocation(program, "uTexture");

        textureId = createTexture();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        ByteBuffer frameToRender = null;

        synchronized (frameLock) {
            if (pendingFrame != null) {
                frameToRender = pendingFrame;
                pendingFrame = null;
            }
        }

        if (frameToRender != null) {
            uploadFrame(frameToRender);
        }

        drawTexture();
    }

    private void uploadFrame(ByteBuffer rgbaBuffer) {
        rgbaBuffer.position(0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (!textureCreated) {
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    frameWidth,
                    frameHeight,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    rgbaBuffer
            );

            textureCreated = true;
        } else {
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    frameWidth,
                    frameHeight,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    rgbaBuffer
            );
        }

        checkGlError("uploadFrame");
    }

    private void drawTexture() {
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(uTextureLocation, 0);

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

        checkGlError("drawTexture");
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
}