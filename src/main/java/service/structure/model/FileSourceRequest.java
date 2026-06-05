package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос полного исходника: simple или qualified имя класса/файла.
 */
@Schema(description = "Запрос исходника файла по имени класса или файла")
public record FileSourceRequest(

        @NotNull(message = "session must not be null")
        @Valid
        SessionRequest session,

        @Schema(description = "Simple или qualified имя", example = "com.example.UserService")
        @NotBlank(message = "name must not be blank")
        String name
) {}
