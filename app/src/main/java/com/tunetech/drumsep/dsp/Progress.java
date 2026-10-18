package com.tunetech.drumsep.dsp;

public interface Progress {
    /** @return false to cancel */
    boolean onProgress(int done, int total);
}
