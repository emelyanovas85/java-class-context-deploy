package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Запрос исходников: simple или qualified имена классов/файлов.
 */
@Schema(description = "Запрос исходников файлов по именам классов или файлов")
public record FileSourceRequest(

        @Schema(description = "Короткий uid сессии", example = "k7Fm2xQp")
        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Schema(description = "Simple или qualified имена", example = "[\"UserService\", \"com.example.Foo\"]")
        @NotEmpty(message = "names must not be empty")
        List<@NotBlank(message = "name must not be blank") String> names
) {}
