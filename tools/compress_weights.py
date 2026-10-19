"""Weight-only int8 (per-channel) compression: Conv/MatMul weights stored as int8 + DequantizeLinear, compute stays fp32."""
import onnx, numpy as np, sys, os
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
inits = {i.name: i for i in g.initializer}
new_nodes = []; done = {}
count = 0; saved = 0
for n in g.node:
    for idx, name in enumerate(n.input):
        if name in inits and n.op_type in ("Conv", "ConvTranspose", "MatMul", "Gemm") and idx == 1:
            w = numpy_helper.to_array(inits[name]).astype(np.float32)
            if w.ndim < 2 or w.size < 4096: continue
            if name not in done:
                axis = 1 if n.op_type == "ConvTranspose" else 0
                if n.op_type in ("MatMul",) : axis = w.ndim - 1   # per output column
                mx = np.max(np.abs(w), axis=tuple(a for a in range(w.ndim) if a != axis), keepdims=True)
                scale = (mx / 127.0).astype(np.float32); scale[scale == 0] = 1e-8
                q = np.clip(np.round(w / scale), -127, 127).astype(np.int8)
                g.initializer.remove(inits[name])
                g.initializer.append(numpy_helper.from_array(q, name + "_q"))
                g.initializer.append(numpy_helper.from_array(scale.reshape(-1).astype(np.float32), name + "_s"))
                g.initializer.append(numpy_helper.from_array(np.zeros(scale.size, np.int8), name + "_z"))
                new_nodes.append(helper.make_node("DequantizeLinear", [name + "_q", name + "_s", name + "_z"], [name + "_dq"], axis=axis, name=name + "_dq_node"))
                done[name] = name + "_dq"; count += 1; saved += w.size * 3
            n.input[idx] = done[name]
# DQ nodes must come before use: prepend
for nd in reversed(new_nodes): g.node.insert(0, nd)
# opset for DQ axis
onnx.save(m, dst)
print("quantized tensors", count, "size MB %.1f -> %.1f" % (os.path.getsize(src)/1e6, os.path.getsize(dst)/1e6))
