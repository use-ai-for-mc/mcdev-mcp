package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import dev.mcdevmcp.analysis.classfile.ClassFileType;
import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.IndexBuildException;

import javax.lang.model.element.*;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import java.lang.constant.ClassDesc;
import java.util.*;

final class JavacDeclarationReader {
    private JavacDeclarationReader() {
    }

    static ParsedType parseType(CompilationUnitTree unit, ClassTree tree, List<? extends Tree> declaredMembers, Map<MethodTree, List<? extends VariableTree>> declaredMethodParameters, Map<Tree, SourceRange> declaredRanges, DecodedSource source, ClassFileTypeCatalog catalog, Trees trees, TypeResolver resolver) throws IndexBuildException {
        TreePath typePath = TreePath.getPath(unit, tree);
        if (!(trees.getElement(typePath) instanceof TypeElement element)) {
            throw new IndexBuildException("Unable to resolve source declaration at " + source.relativeName());
        }
        String binaryName = resolver.binaryName(element);
        ElementKind kind = element.getKind();
        if (!Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.ENUM, ElementKind.RECORD, ElementKind.ANNOTATION_TYPE).contains(kind)) {
            throw new IndexBuildException("Unsupported top-level declaration kind " + kind + " for " + binaryName);
        }
        Optional<ClassDesc> superclass;
        List<ClassDesc> interfaces;
        Optional<ClassFileType> catalogType = catalog.find(binaryName);
        if (catalogType.isPresent()) {
            superclass = catalogType.orElseThrow().superclass();
            interfaces = catalogType.orElseThrow().interfaces();
        }
        else {
            superclass = element.getSuperclass().getKind() == TypeKind.NONE ? Optional.empty() : Optional.of(resolver.erasedDescriptor(element.getSuperclass()));
            List<ClassDesc> resolvedInterfaces = new ArrayList<>();
            for (var implemented : element.getInterfaces()) {
                resolvedInterfaces.add(resolver.erasedDescriptor(implemented));
            }
            interfaces = List.copyOf(resolvedInterfaces);
        }
        List<ParsedField> fields = new ArrayList<>();
        List<ParsedMethod> methods = new ArrayList<>();
        List<VariableTree> recordComponents = recordComponents(unit, element, declaredMembers, declaredRanges, trees, resolver);
        for (Tree member : declaredMembers) {
            if (member instanceof VariableTree variable) {
                Element memberElement = trees.getElement(TreePath.getPath(unit, variable));
                if (memberElement instanceof VariableElement field && Set.of(ElementKind.FIELD, ElementKind.ENUM_CONSTANT, ElementKind.RECORD_COMPONENT).contains(field.getKind())) {
                    fields.add(new ParsedField(fields.size(), field.getSimpleName().toString(), resolver.semanticType(field.asType()), field.getModifiers(), declaredRange(variable, declaredRanges)));
                }
            }
            else if (member instanceof MethodTree method) {
                methods.add(parseMethod(unit, method, declaredMethodParameters.getOrDefault(method, List.of()), recordComponents, declaredRanges, methods.size(), trees, resolver));
            }
        }
        return new ParsedType(source.root(), source.relativePath(), source.packageName(), binaryName, element.getSimpleName().toString(), kind, superclass, interfaces, fields, methods, declaredRange(tree, declaredRanges));
    }

    static void captureRange(CompilationUnitTree unit, Tree tree, SourcePositions positions, Map<Tree, SourceRange> ranges) throws IndexBuildException {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start >= 0 && end < start && tree instanceof VariableTree) {
            return;
        }
        if (start < 0 || end < start || start > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            throw new IndexBuildException("Javac did not provide a valid source range for " + tree.getKind() + " '" + tree + "' in " + unit.getSourceFile().getName() + ": " + start + ".." + end);
        }
        long endPositionForLine = end == start ? start : end - 1;
        long startLine = unit.getLineMap().getLineNumber(start);
        long endLine = unit.getLineMap().getLineNumber(endPositionForLine);
        if (startLine < 1 || endLine < startLine || startLine > Integer.MAX_VALUE || endLine > Integer.MAX_VALUE) {
            throw new IndexBuildException("Javac did not provide a valid line range in " + unit.getSourceFile().getName());
        }
        ranges.put(tree, new SourceRange((int) start, (int) end, (int) startLine, (int) endLine));
    }

    private static ParsedMethod parseMethod(CompilationUnitTree unit, MethodTree tree, List<? extends VariableTree> declaredParameters, List<VariableTree> recordComponents, Map<Tree, SourceRange> declaredRanges, int ordinal, Trees trees, TypeResolver resolver) throws IndexBuildException {
        if (!(trees.getElement(TreePath.getPath(unit, tree)) instanceof ExecutableElement element)) {
            throw new IndexBuildException("Unable to resolve method declaration " + tree.getName());
        }
        boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
        String name = constructor ? "<init>" : element.getSimpleName().toString();
        ExecutableType executableType = (ExecutableType) element.asType();
        Optional<String> returnType = constructor ? Optional.empty() : Optional.of(resolver.semanticType(executableType.getReturnType()));
        List<? extends VariableTree> parameterTrees = declaredParameters;
        boolean compactProjection = !parameterTrees.isEmpty() && parameterTrees.stream().noneMatch(declaredRanges::containsKey);
        if (compactProjection) {
            if (!constructor || !matchesRecordComponents(element.getParameters(), recordComponents, unit, trees, resolver)) {
                throw new IndexBuildException("Javac did not provide exact supported parameter ranges for " + name);
            }
            parameterTrees = recordComponents;
        }
        if (parameterTrees.size() != element.getParameters().size()) {
            throw new IndexBuildException("Unable to match source parameter ranges for " + name);
        }
        List<ParsedParameter> parameters = new ArrayList<>();
        for (int index = 0; index < element.getParameters().size(); index++) {
            VariableElement parameter = element.getParameters().get(index);
            VariableTree parameterTree = parameterTrees.get(index);
            parameters.add(new ParsedParameter(index, parameter.getSimpleName().toString(), resolver.semanticType(parameter.asType()), element.isVarArgs() && index == element.getParameters().size() - 1, declaredRange(parameterTree, declaredRanges)));
        }
        return new ParsedMethod(ordinal, name, resolver.methodDescriptor(executableType), returnType, element.getModifiers(), constructor, parameters, declaredRange(tree, declaredRanges));
    }

    private static List<VariableTree> recordComponents(CompilationUnitTree unit, TypeElement element, List<? extends Tree> declaredMembers, Map<Tree, SourceRange> declaredRanges, Trees trees, TypeResolver resolver) throws IndexBuildException {
        List<VariableTree> result = new ArrayList<>();
        for (RecordComponentElement component : element.getRecordComponents()) {
            List<VariableTree> matches = new ArrayList<>();
            for (Tree member : declaredMembers) {
                if (member instanceof VariableTree variable && declaredRanges.containsKey(variable) && variable.getName().contentEquals(component.getSimpleName())) {
                    Element candidate = trees.getElement(TreePath.getPath(unit, variable));
                    if (candidate instanceof VariableElement field && resolver.semanticType(field.asType()).equals(resolver.semanticType(component.asType()))) {
                        matches.add(variable);
                    }
                }
            }
            if (matches.size() != 1) {
                throw new IndexBuildException("Unable to match exact compiler-owned source range for record component " + component.getSimpleName());
            }
            result.add(matches.getFirst());
        }
        return List.copyOf(result);
    }

    private static boolean matchesRecordComponents(List<? extends VariableElement> parameters, List<VariableTree> components, CompilationUnitTree unit, Trees trees, TypeResolver resolver) throws IndexBuildException {
        if (parameters.size() != components.size()) {
            return false;
        }
        for (int index = 0; index < parameters.size(); index++) {
            VariableElement parameter = parameters.get(index);
            VariableTree component = components.get(index);
            Element candidate = trees.getElement(TreePath.getPath(unit, component));
            if (!(candidate instanceof VariableElement field) || !parameter.getSimpleName().contentEquals(component.getName()) || !resolver.semanticType(parameter.asType()).equals(resolver.semanticType(field.asType()))) {
                return false;
            }
        }
        return true;
    }

    private static SourceRange declaredRange(Tree tree, Map<Tree, SourceRange> ranges) throws IndexBuildException {
        SourceRange range = ranges.get(tree);
        if (range == null) {
            throw new IndexBuildException("No captured source range for " + tree.getKind() + " '" + tree + "'");
        }
        return range;
    }
}
