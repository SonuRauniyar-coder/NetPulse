package com.netpulse.common.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {
    private final ProcessRunner runner = new ProcessRunner();

    @Test void capturesOutputAndExitCode() {
        ProcessResult r = runner.run(List.of("sh", "-c", "echo out; echo err 1>&2; exit 3"), Duration.ofSeconds(5));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.output()).contains("out").contains("err");
        assertThat(r.timedOut()).isFalse();
    }

    @Test void killsProcessThatExceedsTimeout() {
        long start = System.currentTimeMillis();
        ProcessResult r = runner.run(List.of("sleep", "30"), Duration.ofMillis(300));
        assertThat(r.timedOut()).isTrue();
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);
    }

    @Test void argumentsAreNotInterpretedByAShell() {
        ProcessResult r = runner.run(List.of("echo", "a; touch /tmp/should-not-exist-np $(id)"), Duration.ofSeconds(5));
        assertThat(r.output()).contains("a; touch /tmp/should-not-exist-np $(id)");
    }

    @Test void missingToolIsReportedClearly() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-real-tool-xyz"), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ProcessFailureException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ProcessFailureException.Reason.TOOL_MISSING));
    }

    @Test void largeOutputDoesNotDeadlock() {
        ProcessResult r = runner.run(List.of("sh", "-c", "yes x | head -c 1000000"), Duration.ofSeconds(10));
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output()).isNotEmpty();
    }
}
