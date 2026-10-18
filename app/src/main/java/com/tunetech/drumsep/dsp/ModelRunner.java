package com.tunetech.drumsep.dsp;

/** Runs one spectrogram chunk through a neural network. Input/output are flat NCHW float arrays. */
public interface ModelRunner extends AutoCloseable {
    float[] run(float[] input) throws Exception;
    @Override default void close() {}
}
