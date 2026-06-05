package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Запрос исходников: simple или qualified имена классов/файлов.
 */
@Schema(
        description = "Полные исходники по списку имён — repo и jar",
        example = """
                {
                  "sessionId": "k7Fm2xQp",
                  "names": ["UserService", "com.example.dto.UserDto"]
                }
                """
)
public record FileSourceRequest(

        @Schema(description = "Uid сессии", example = "k7Fm2xQp", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Schema(description = "Simple или qualified имена; по каждому — все совпадения в repo и jar",
                example = "[\"UserService\", \"com.example.Foo\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "names must not be empty")
        List<@NotBlank(message = "name must not be blank") String> names
) {}
