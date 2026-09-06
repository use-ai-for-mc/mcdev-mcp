package dev.mcdevmcp.analysis.classfile;

import java.lang.constant.ClassDesc;

public final class ClassDescriptors {
    private ClassDescriptors() {
    }

    public static String binaryName(ClassDesc descriptor) {
        String value = descriptor.descriptorString();
        if (!value.startsWith("L") || !value.endsWith(";")) {
            throw new IllegalArgumentException("Expected a class or interface descriptor, got " + value);
        }
        return value.substring(1, value.length() - 1).replace('/', '.');
    }
}