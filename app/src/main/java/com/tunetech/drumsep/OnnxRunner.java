package com.tunetech.drumsep;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.tunetech.drumsep.dsp.ModelRunner;

import java.nio.FloatBuffer;
import java.util.Collections;

/** ONNX Runtime wrapper: one fixed-shape input, one output. */
public final class OnnxRunner implements ModelRunner {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final long[] shape;

    public OnnxRunner(String modelPath, long[] inputShape, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        o.setInterOpNumThreads(1);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        o.setMemoryPatternOptimization(true);
        try { o.addConfigEntry("session.intra_op.allow_spinning", "0"); } catch (Exception ignored) {}
        session = env.createSession(modelPath, o);
        inputName = session.getInputNames().iterator().next();
        shape = inputShape;
    }

    @Override
    public float[] run(float[] input) throws OrtException {
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
            OnnxTensor out = (OnnxTensor) r.get(0);
            FloatBuffer fb = out.getFloatBuffer();
            float[] y = new float[fb.remaining()];
            fb.get(y);
            return y;
        }
    }

    @Override public void close() {
        try { session.close(); } catch (Exception ignored) {}
    }
}
