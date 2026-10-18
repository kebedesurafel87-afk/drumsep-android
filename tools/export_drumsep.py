"""Convert MDX23C DrumSep (PyTorch .ckpt) to ONNX for the Android app.
Usage: python export_drumsep.py <ckpt> <config.yaml> <out.onnx>
Input : (1, 4, 1024, 256)  spectrogram [L_re, L_im, R_re, R_im] x dim_f x frames
Output: (1, 24, 1024, 256) 6 stems (kick, snare, toms, hh, ride, crash) x 4
"""
import sys, os, yaml, torch, onnx, numpy as np
from ml_collections import ConfigDict
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tfc_tdf_v3 import TFC_TDF_net

ckpt, cfgp, out = sys.argv[1:4]
with open(cfgp) as f:
    cfg = ConfigDict(yaml.load(f, Loader=yaml.FullLoader))
model = TFC_TDF_net(cfg, device=torch.device("cpu"))
model.load_state_dict(torch.load(ckpt, map_location="cpu"))
model.eval()


class SpecNet(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m

    def forward(self, x):
        m = self.m
        mix = x = m.cac2cws(x)
        first = x = m.first_conv(x)
        x = x.transpose(-1, -2)
        enc = []
        for b in m.encoder_blocks:
            x = b.tfc_tdf(x); enc.append(x); x = b.downscale(x)
        x = m.bottleneck_block(x)
        for b in m.decoder_blocks:
            x = b.upscale(x); x = torch.cat([x, enc.pop()], 1); x = b.tfc_tdf(x)
        x = x.transpose(-1, -2)
        x = x * first
        x = m.final_conv(torch.cat([mix, x], 1))
        return m.cws2cac(x)


net = SpecNet(model).eval()
dummy = torch.randn(1, 4, cfg.audio.dim_f, cfg.inference.dim_t)
torch.onnx.export(net, dummy, out, opset_version=17, input_names=["spec"], output_names=["stems"],
                  dynamo=False, do_constant_folding=True)
onnx.checker.check_model(onnx.load(out))
import onnxruntime as ort
s = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
chunk = cfg.audio.hop_length * (cfg.inference.dim_t - 1)
wav = torch.randn(1, 2, chunk) * 0.1
with torch.no_grad():
    full = model(wav)
    o = torch.from_numpy(s.run(None, {"spec": model.stft(wav).numpy()})[0]).reshape(1, 6, 4, cfg.audio.dim_f, -1)
    rec = model.stft.inverse(o)
diff = float((full - rec).abs().max())
print("size bytes", os.path.getsize(out), "max diff vs pytorch", diff)
assert diff < 1e-4
