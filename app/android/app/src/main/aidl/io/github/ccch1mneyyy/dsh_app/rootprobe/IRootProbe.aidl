package io.github.ccch1mneyyy.dsh_app.rootprobe;

import android.os.ParcelFileDescriptor;

interface IRootProbe {
    String identity();

    // Runs runc with the given args; returns the stable container id.
    // App supplies stdin/stdout/stderr pipes; App-side pump reads them directly.
    String start(in List<String> argv, String containerId, String stateRoot, in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr);

    // Stops the container: runc kill <id> KILL, waits for exit, then runc delete --force <id>.
    // Returns "ok" on success, or a short reason code ("timeout", "stillRunning", "deleteFailed").
    String stop(String containerId, String stateRoot);
}