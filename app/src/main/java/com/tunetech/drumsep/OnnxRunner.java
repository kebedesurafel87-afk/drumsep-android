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

    /** 0 = CPU, 1 = XNNPACK, 2 = NNAPI (phone NPU/GPU). Falls back to CPU if the engine cannot be created. */
    public static final int ENGINE_CPU = 0, ENGINE_XNNPACK = 1, ENGINE_NNAPI = 2;
    public final String engineUsed;

    public OnnxRunner(String modelPath, long[] inputShape, int threads) throws OrtException {
        this(modelPath, inputShape, threads, ENGINE_CPU);
    }

    public OnnxRunner(String modelPath, long[] inputShape, int threads, int engine) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession s = null;
        String used = "CPU";
        if (engine != ENGINE_CPU) {
            try {
                OrtSession.SessionOptions o = baseOptions(threads);
                if (engine == ENGINE_XNNPACK) {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("intra_op_num_threads", String.valueOf(threads));
                    o.addXnnpack(m);
                    used = "XNNPACK";
                } else {
                    o.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16));
                    used = "NPU";
                }
                s = env.createSession(modelPath, o);
            } catch (Throwable t) {
                s = null; used = "CPU";
            }
        }
        if (s == null) s = env.createSession(modelPath, baseOptions(threads));
        session = s;
        engineUsed = used;
        inputName = session.getInputNames().iterator().next();
        shape = inputShape;
    }

    private static OrtSession.SessionOptions baseOptions(int threads) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        o.setInterOpNumThreads(1);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        o.setMemoryPatternOptimization(true);
        try { o.addConfigEntry("session.intra_op.allow_spinning", "0"); } catch (Exception ignored) {}
        return o;
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
