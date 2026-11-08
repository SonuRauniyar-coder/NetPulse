package com.netpulse.common.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs an external networking tool (ping, traceroute, arp ...) safely.
 *
 * <p>Security: the command is a {@code List<String>} handed straight to {@link ProcessBuilder}. No shell
 * ({@code sh -c} / {@code cmd /c}) is ever involved, so characters like {@code ; | & $()} in an argument are
 * just bytes, never interpreted. Callers must still validate every argument (see TargetValidator).
 *
 * <p>Robustness: output is drained on a separate virtual thread. If we waited for the process first and read
 * afterwards, a chatty process could fill the OS pipe buffer (~64 KB), block on write, and never exit.
 * Processes that exceed the timeout are killed together with any children, and we always clean up.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    public ProcessResult run(List<String> command, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        // Force English, predictable output on Linux/macOS (does not affect Windows, which localizes anyway).
        pb.environment().put("LC_ALL", "C");
        pb.environment().put("LANG", "C");

        long start = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw classifyStartFailure(command.getFirst(), e);
        }

        CompletableFuture<String> output = CompletableFuture.supplyAsync(
                () -> readAll(process.getInputStream()), r -> Thread.ofVirtual().start(r));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                kill(process);
                String partial = output.completeOnTimeout("", 500, TimeUnit.MILLISECONDS).get();
                log.debug("Process {} timed out after {} ms", command.getFirst(), timeout.toMillis());
                return new ProcessResult(-1, partial, true, elapsedMs(start));
            }
            String out = output.get(2, TimeUnit.SECONDS);
            return new ProcessResult(process.exitValue(), out, false, elapsedMs(start));
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "", true, elapsedMs(start));
        } catch (ExecutionException | TimeoutException e) {
            kill(process);
            return new ProcessResult(process.isAlive() ? -1 : process.exitValue(), "", false, elapsedMs(start));
        } finally {
            if (process.isAlive()) kill(process);
            closeQuietly(process);
        }
    }

    private static ProcessFailureException classifyStartFailure(String tool, IOException e) {
        String msg = String.valueOf(e.getMessage());
        // JDK reports "error=2, No such file or directory" (Unix) / "CreateProcess error=2" (Windows)
        if (msg.contains("error=2") || msg.contains("No such file") || msg.contains("cannot find")) {
            return new ProcessFailureException(ProcessFailureException.Reason.TOOL_MISSING,
                    "'" + tool + "' is not installed or not on PATH", e);
        }
        if (msg.contains("error=13") || msg.contains("Permission denied") || msg.contains("error=5")) {
            return new ProcessFailureException(ProcessFailureException.Reason.PERMISSION_DENIED,
                    "Permission denied starting '" + tool + "'", e);
        }
        return new ProcessFailureException(ProcessFailureException.Reason.IO_ERROR,
                "Failed to start '" + tool + "': " + msg, e);
    }

    private static String readAll(InputStream in) {
        try (in) {
            byte[] data = in.readNBytes(MAX_OUTPUT_BYTES);
            in.transferTo(java.io.OutputStream.nullOutputStream()); // keep draining so the process never blocks
            return new String(data, Charset.defaultCharset());
        } catch (IOException e) {
            return "";
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void closeQuietly(Process p) {
        try { p.getOutputStream().close(); } catch (IOException ignored) { }
        try { p.getInputStream().close(); } catch (IOException ignored) { }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
