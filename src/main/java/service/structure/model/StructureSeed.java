package service.structure.model;

/**
 * Корень обхода структуры: файл репозитория или класс из sources.jar.
 */
public sealed interface StructureSeed permits StructureSeed.RepoFile, StructureSeed.DependencyClass {

    /** Путь {@code src/.../Foo.java} в pinned source/target. */
    record RepoFile(String path) implements StructureSeed {}

    /** Qualified name класса в dependencySources. */
    record DependencyClass(String qualifiedName) implements StructureSeed {}
}
