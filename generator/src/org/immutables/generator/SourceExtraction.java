package org.immutables.generator;

import com.google.common.collect.Maps;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.eclipse.jdt.internal.compiler.apt.model.ElementImpl;
import org.eclipse.jdt.internal.compiler.apt.model.ExecutableElementImpl;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

public final class SourceExtraction {
  private SourceExtraction() {}

  public static final class Imports {
    private static final Imports EMPTY = new Imports(
        ImmutableSet.<String>of(),
        ImmutableMap.<String, String>of());

    public final ImmutableSet<String> all;
    public final ImmutableMap<String, String> classes;

    private Imports(Set<String> all, Map<String, String> classes) {
      this.all = ImmutableSet.copyOf(all);
      this.classes = ImmutableMap.copyOf(classes);
    }

    public static Imports of(Set<String> all, Map<String, String> classes) {
      if (all.isEmpty() && classes.isEmpty()) {
        return EMPTY;
      }
      if (!all.containsAll(classes.values())) {
        // This check initially appeared as some imports might be skipped,
        // but all classes imported are tracked, but it should be not a problem
      }
      return new Imports(all, classes);
    }

    public static Imports empty() {
      return EMPTY;
    }

    public boolean isEmpty() {
      return this == EMPTY;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("all", all)
          .add("classes", classes)
          .toString();
    }
  }

  public static Imports readImports(ProcessingEnvironment environment, TypeElement element) {
    try {
      return PostprocessingMachine.collectImports(
          SourceExtraction.extract(environment, element));

    } catch (IOException cannotReadSourceFile) {
      environment.getMessager().printMessage(
          Diagnostic.Kind.MANDATORY_WARNING,
          String.format("Could not read source files to collect imports for %s[%s.class]: %s",
              element,
              element.getClass().getName(),
              cannotReadSourceFile));

      return Imports.empty();
    }
  }

  public static CharSequence extract(ProcessingEnvironment environment, TypeElement element) throws IOException {
    return EXTRACTOR.extract(environment, element);
  }

  interface SourceExtractor {
    CharSequence UNABLE_TO_EXTRACT = "";

    CharSequence extract(ProcessingEnvironment environment, TypeElement typeElement) throws IOException;

    CharSequence extractReturnType(ExecutableElement executableElement);
  }

  private static final SourceExtractor DEFAULT_EXTRACTOR = new SourceExtractor() {
    @Override
    public CharSequence extract(ProcessingEnvironment environment, TypeElement element) throws IOException {
      FileObject resource = environment.getFiler().getResource(
          StandardLocation.SOURCE_PATH, "", toFilename(element));

      return resource.getCharContent(true);
    }

    private String toFilename(TypeElement element) {
      return element.getQualifiedName().toString().replace('.', '/') + ".java";
    }

    @Override
    public CharSequence extractReturnType(ExecutableElement executableElement) {
      return UNABLE_TO_EXTRACT;
    }
  };

  private static final class JavacSourceExtractor implements SourceExtractor {
    // Triggers loading of class that may be absent in classpath
    static {
      ClassSymbol.class.getCanonicalName();
    }

    @Override
    public CharSequence extract(ProcessingEnvironment environment, TypeElement typeElement) throws IOException {
      if (typeElement instanceof ClassSymbol) {
        JavaFileObject sourceFile = ((ClassSymbol) typeElement).sourcefile;
        return sourceFile.getCharContent(true);
      }
      return UNABLE_TO_EXTRACT;
    }

    @Override
    public CharSequence extractReturnType(ExecutableElement executableElement) {
      return UNABLE_TO_EXTRACT;
    }
  }

  private static final class EclipseSourceExtractor implements SourceExtractor {
    // Triggers loading of class that may be absent in classpath
    static {
      ElementImpl.class.getCanonicalName();
    }

    @Override
    public CharSequence extract(ProcessingEnvironment environment, TypeElement typeElement) throws IOException {
      if (typeElement instanceof ElementImpl) {
        Binding binding = ((ElementImpl) typeElement)._binding;
        if (binding instanceof SourceTypeBinding) {
          CompilationUnitDeclaration unit = ((SourceTypeBinding) binding).scope.referenceCompilationUnit();
          char[] contents = unit.compilationResult.compilationUnit.getContents();
          return CharBuffer.wrap(contents);
        }
      }
      return UNABLE_TO_EXTRACT;
    }

    @Override
    public CharSequence extractReturnType(ExecutableElement executableElement) {
      if (executableElement instanceof ExecutableElementImpl) {
        Binding binding = ((ExecutableElementImpl) executableElement)._binding;
        if (binding instanceof MethodBinding) {
          MethodBinding methodBinding = (MethodBinding) binding;

          @Nullable AbstractMethodDeclaration sourceMethod = methodBinding.sourceMethod();
          if (sourceMethod != null) {
            CharSequence rawType = getRawType(methodBinding);
            char[] content = sourceMethod.compilationResult.compilationUnit.getContents();

            int sourceEnd = methodBinding.sourceStart();// intentionaly
            int sourceStart = scanForTheSourceStart(content, sourceEnd);

            char[] methodTest = Arrays.copyOfRange(content, sourceStart, sourceEnd);

            Entry<String, List<String>> extracted =
                SourceTypes.extract(String.valueOf(methodTest));

            return SourceTypes.stringify(
                Maps.immutableEntry(rawType.toString(), extracted.getValue()));
          }
        }
      }
      return UNABLE_TO_EXTRACT;
    }

    private int scanForTheSourceStart(char[] content, int sourceEnd) {
      int i = sourceEnd;
      for (; i >= 0; i--) {
        char c = content[i];
        // FIXME how else I can scan?
        if (c == '\n') {
          return i;
        }
      }
      return i;
    }

    private CharSequence getRawType(MethodBinding methodBinding) {
      TypeBinding returnType = methodBinding.returnType;
      char[] sourceName = returnType.sourceName();
      if (sourceName == null) {
        sourceName = new char[] {};
      }
      return CharBuffer.wrap(sourceName);
    }
  }

  private static final class CompositeExtractor implements SourceExtractor {
    private final SourceExtractor[] extractors;

    CompositeExtractor(List<SourceExtractor> extractors) {
      this.extractors = extractors.toArray(new SourceExtractor[extractors.size()]);
    }

    @Override
    public CharSequence extract(ProcessingEnvironment environment, TypeElement typeElement) throws IOException {
      for (SourceExtractor extractor : extractors) {
        CharSequence source = extractor.extract(environment, typeElement);
        if (!source.equals(UNABLE_TO_EXTRACT)) {
          return source;
        }
      }
      return DEFAULT_EXTRACTOR.extract(environment, typeElement);
    }

    @Override
    public CharSequence extractReturnType(ExecutableElement executableElement) {
      for (SourceExtractor extractor : extractors) {
        CharSequence source = extractor.extractReturnType(executableElement);
        if (!source.equals(UNABLE_TO_EXTRACT)) {
          return source;
        }
      }
      return DEFAULT_EXTRACTOR.extractReturnType(executableElement);
    }
  }

  private static SourceExtractor createExtractor() {
    List<SourceExtractor> extractors = Lists.newArrayListWithCapacity(2);
    try {
      extractors.add(new JavacSourceExtractor());
    } catch (Throwable javacClassesNotAwailable) {
    }
    try {
      extractors.add(new EclipseSourceExtractor());
    } catch (Throwable eclipseClassesNotAwailable) {
    }
    return new CompositeExtractor(extractors);
  }

  private static final SourceExtractor EXTRACTOR = createExtractor();

  public static CharSequence getReturnTypeString(ExecutableElement method) {
    return EXTRACTOR.extractReturnType(method);
  }
}
