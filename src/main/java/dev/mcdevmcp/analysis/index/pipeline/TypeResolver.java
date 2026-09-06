package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TypeResolver {
    private final Elements elements;
    private final Types types;

    TypeResolver(Elements elements, Types types) {
        this.elements = Objects.requireNonNull(elements, "elements");
        this.types = Objects.requireNonNull(types, "types");
    }

    private static boolean containsError(TypeMirror type) {
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        return switch (type) {
            case ArrayType array -> containsError(array.getComponentType());
            case DeclaredType declared ->
                    containsError(declared.getEnclosingType()) || declared.getTypeArguments().stream().anyMatch(TypeResolver::containsError);
            case WildcardType wildcard ->
                    wildcard.getExtendsBound() != null && containsError(wildcard.getExtendsBound()) || wildcard.getSuperBound() != null && containsError(wildcard.getSuperBound());
            case IntersectionType intersection ->
                    intersection.getBounds().stream().anyMatch(TypeResolver::containsError);
            default -> false;
        };
    }

    ClassDesc erasedDescriptor(TypeMirror type) throws IndexBuildException {
        TypeMirror erased = types.erasure(requireResolved(type));
        return descriptor(erased);
    }

    MethodTypeDesc methodDescriptor(ExecutableType method) throws IndexBuildException {
        List<ClassDesc> parameters = new ArrayList<>();
        for (TypeMirror parameter : method.getParameterTypes()) {
            parameters.add(erasedDescriptor(parameter));
        }
        return MethodTypeDesc.of(erasedDescriptor(method.getReturnType()), parameters.toArray(ClassDesc[]::new));
    }

    String semanticType(TypeMirror type) throws IndexBuildException {
        return encode(requireResolved(type));
    }

    String binaryName(TypeElement element) throws IndexBuildException {
        String binaryName = elements.getBinaryName(Objects.requireNonNull(element, "element")).toString();
        if (binaryName.isBlank()) {
            throw new IndexBuildException("Type element has no binary name: " + element);
        }
        return binaryName;
    }

    private TypeMirror requireResolved(TypeMirror type) throws IndexBuildException {
        Objects.requireNonNull(type, "type");
        if (containsError(type)) {
            throw new IndexBuildException("Unable to resolve stored semantic type: " + type);
        }
        return type;
    }

    private ClassDesc descriptor(TypeMirror type) throws IndexBuildException {
        return switch (type.getKind()) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case SHORT -> ConstantDescs.CD_short;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case CHAR -> ConstantDescs.CD_char;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case VOID -> ConstantDescs.CD_void;
            case ARRAY -> descriptor(((ArrayType) type).getComponentType()).arrayType();
            case DECLARED -> ClassDesc.of(binaryName((DeclaredType) type));
            default ->
                    throw new IndexBuildException("Stored type has no faithful JVM descriptor: " + type + " (" + type.getKind() + ")");
        };
    }

    private String encode(TypeMirror type) throws IndexBuildException {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID ->
                    type.getKind().name().toLowerCase(java.util.Locale.ROOT);
            case ARRAY -> encode(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> encodeDeclared((DeclaredType) type);
            case TYPEVAR -> ((TypeVariable) type).asElement().getSimpleName().toString();
            case WILDCARD -> encodeWildcard((WildcardType) type);
            case INTERSECTION -> join(((IntersectionType) type).getBounds(), " & ");
            case NONE -> "";
            default ->
                    throw new IndexBuildException("Unsupported stored semantic type: " + type + " (" + type.getKind() + ")");
        };
    }

    private String encodeDeclared(DeclaredType type) throws IndexBuildException {
        String binaryName = binaryName(type);
        String current = type.getTypeArguments().isEmpty() ? binaryName : binaryName + "<" + join(type.getTypeArguments(), ", ") + ">";
        TypeMirror enclosing = type.getEnclosingType();
        if (enclosing.getKind() == TypeKind.NONE || type.asElement().getModifiers().contains(Modifier.STATIC)) {
            return current;
        }
        return encode(enclosing) + "::" + current;
    }

    private String encodeWildcard(WildcardType type) throws IndexBuildException {
        if (type.getExtendsBound() != null) {
            return "? extends " + encode(type.getExtendsBound());
        }
        if (type.getSuperBound() != null) {
            return "? super " + encode(type.getSuperBound());
        }
        return "?";
    }

    private String join(List<? extends TypeMirror> mirrors, String separator) throws IndexBuildException {
        List<String> values = new ArrayList<>(mirrors.size());
        for (TypeMirror mirror : mirrors) {
            values.add(encode(mirror));
        }
        return String.join(separator, values);
    }

    private String binaryName(DeclaredType type) throws IndexBuildException {
        if (!(type.asElement() instanceof TypeElement element)) {
            throw new IndexBuildException("Declared type has no type element: " + type);
        }
        return binaryName(element);
    }
}