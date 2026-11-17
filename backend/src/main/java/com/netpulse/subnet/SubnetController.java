package com.netpulse.subnet;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Pure calculation with no network I/O, so every signed-in role may use it. */
@RestController
@RequestMapping("/api/v1/subnet")
@Tag(name = "Subnet calculator")
public class SubnetController {
    public record SegmentDto(@NotBlank(message = "Name is required") @Size(max = 64) String name,
                             @Min(value = 1, message = "At least 1 host") @Max(value = 4294967294L, message = "Too many hosts") long hosts) { }

    public record VlsmRequest(@NotBlank(message = "Base network is required") String network,
                              @NotEmpty(message = "Add at least one segment") @Size(max = 256, message = "At most 256 segments") List<@NotNull @Valid SegmentDto> segments) { }

    @GetMapping("/calculate")
    public SubnetCalculator.SubnetInfo calculate(@RequestParam String cidr) {
        return SubnetCalculator.calculate(cidr);
    }

    @PostMapping("/vlsm")
    public SubnetCalculator.VlsmResult vlsm(@Valid @RequestBody VlsmRequest req) {
        return SubnetCalculator.vlsm(req.network(), req.segments().stream()
                .map(s -> new SubnetCalculator.Requirement(s.name().trim(), s.hosts())).toList());
    }
}
