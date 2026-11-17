package com.netpulse.system;

import com.netpulse.common.config.NetPulseProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive facts the UI needs before login (e.g. to label demo data). */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System")
public class SystemController {
    public record SystemInfo(boolean demoMode, boolean monitoringEnabled) { }

    private final NetPulseProperties props;

    public SystemController(NetPulseProperties props) {
        this.props = props;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo(props.demoMode(), props.monitoring().enabled());
    }
}
