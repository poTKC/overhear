package com.potkc.overhear.runtime;

/**
 * Progress of a Soloist download (RuntimeInstaller.ensureSoloist). Called on
 * the installer's thread; post to a Handler to touch views.
 */
public interface ProgressListener {
    /** Download progress 0-100; only reported when the size is known. */
    void onProgress(int percent);

    /** The download finished and the archive is being unpacked. */
    void onExtracting();
}
