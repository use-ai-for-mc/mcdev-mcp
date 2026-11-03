package dev.mcdevmcp.analysis.classfile;

import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public value record ClassFileType(ClassDesc descriptor, Optional<ClassDesc> superclass, List<ClassDesc> interfaces, Set<AccessFlag> accessFlags, Optional<ClassDesc> outerClass, Optional<String> innerName, Optional<ClassDesc> nestHost, List<ClassDesc> nestMembers) {
    public ClassFileType {
        Objects.requireNonNull(descriptor, "descriptor");
        superclass = Optional.ofNullable(superclass).orElseThrow(() -> new NullPointerException("superclass"));
        interfaces = List.copyOf(interfaces);
        accessFlags = Set.copyOf(accessFlags);
        outerClass = Optional.ofNullable(outerClass).orElseThrow(() -> new NullPointerException("outerClass"));
        innerName = Optional.ofNullable(innerName).orElseThrow(() -> new NullPointerException("innerName"));
        nestHost = Optional.ofNullable(nestHost).orElseThrow(() -> new NullPointerException("nestHost"));
        nestMembers = List.copyOf(nestMembers);
    }

    public String binaryName() {
        return ClassDescriptors.binaryName(descriptor);
    }
}