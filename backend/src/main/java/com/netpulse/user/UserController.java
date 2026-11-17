package com.netpulse.user;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private static final String PASSWORD_MESSAGE = "Use 12 to 72 characters";

    /** BCrypt only reads the first 72 bytes, so anything longer would be silently ignored. */
    public record CreateRequest(@NotBlank(message = "Username is required") @Pattern(regexp = "[A-Za-z0-9._-]{3,64}", message = "3-64 letters, digits, . _ -") String username,
                                @NotNull(message = PASSWORD_MESSAGE) @Size(min = 12, max = 72, message = PASSWORD_MESSAGE) String password,
                                @NotNull(message = "Role is required") Role role) { }

    public record UpdateRequest(@NotNull(message = "Role is required") Role role, boolean enabled) { }

    public record PasswordRequest(@NotNull(message = PASSWORD_MESSAGE) @Size(min = 12, max = 72, message = PASSWORD_MESSAGE) String password) { }

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserService.UserDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserService.UserDto create(@Valid @RequestBody CreateRequest r) {
        return service.create(r.username(), r.password(), r.role());
    }

    @PutMapping("/{id}")
    public UserService.UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(id, r.role(), r.enabled());
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody PasswordRequest r) {
        service.resetPassword(id, r.password());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
