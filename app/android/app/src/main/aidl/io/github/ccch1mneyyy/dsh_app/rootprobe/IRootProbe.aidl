package io.github.ccch1mneyyy.dsh_app.rootprobe;

import android.os.ParcelFileDescriptor;

interface IRootProbe {
    String identity();

    // Runs runc with the given args; returns the stable container id.
    // App supplies stdin/stdout/stderr pipes; App-side pump reads them directly.
    String start(in List<String> argv, String containerId, String stateRoot, in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr);

    // Stops the container: runc kill <id> KILL, waits for exit, then runc delete --force <id>.
    // Returns "ok:<code>" after kill, exit, and delete, or a short failure reason.
    String stop(String containerId, String stateRoot);

    // Blocks up to timeoutMs for the runc process to exit.
    // Returns "ok:<code>" (real waitFor code, may be non-zero), "timeout", or "notRunning".
    String waitExit(String containerId, String stateRoot, long timeoutMs);
}