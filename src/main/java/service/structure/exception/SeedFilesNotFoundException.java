package service.structure.exception;

import java.util.List;

/** Имена из {@code names} не резолвятся в пути .java в merged index. → HTTP 400 */
public class SeedFilesNotFoundException extends RuntimeException {

    private final List<String> names;

    public SeedFilesNotFoundException(List<String> names) {
        super("Seed files not found: " + String.join(", ", names));
        this.names = List.copyOf(names);
    }

    public List<String> names() {
        return names;
    }
}
