package dev.mcdevmcp.analysis.callgraph;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.support.Cancellation;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class InvocationExtractor {
    private static final Set<Opcode> ORDINARY_OPCODES = Set.of(Opcode.INVOKEVIRTUAL, Opcode.INVOKEINTERFACE, Opcode.INVOKESTATIC, Opcode.INVOKESPECIAL);
    private static final String LAMBDA_METAFACTORY = "java.lang.invoke.LambdaMetafactory";
    private final ClassFile classFile = ClassFile.of(ClassFile.DebugElementsOption.DROP_DEBUG, ClassFile.LineNumbersOption.PASS_LINE_NUMBERS);

    static Set<Opcode> ordinaryOpcodes() {
        return ORDINARY_OPCODES;
    }

    private static DynamicTarget dynamicTarget(InvokeDynamicInstruction instruction) {
        try {
            DirectMethodHandleDesc bootstrap = instruction.bootstrapMethod();
            String bootstrapOwner = ClassDescriptors.binaryName(bootstrap.owner());
            if (!LAMBDA_METAFACTORY.equals(bootstrapOwner) || !(bootstrap.methodName().equals("metafactory") || bootstrap.methodName().equals("altMetafactory")) || instruction.bootstrapArgs().size() < 2 || !(instruction.bootstrapArgs().get(1) instanceof DirectMethodHandleDesc implementation) || !isMethodKind(implementation.kind())) {
                return null;
            }
            String descriptor = implementation.lookupDescriptor();
            MethodTypeDesc.ofDescriptor(descriptor);
            return new DynamicTarget(ClassDescriptors.binaryName(implementation.owner()), implementation.methodName(), descriptor);
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return null;
        }
    }

    private static boolean isMethodKind(DirectMethodHandleDesc.Kind kind) {
        return switch (kind) {
            case STATIC, INTERFACE_STATIC, VIRTUAL, INTERFACE_VIRTUAL, SPECIAL, INTERFACE_SPECIAL, CONSTRUCTOR -> true;
            case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER -> false;
        };
    }

    Extraction extract(byte[] classBytes) throws InterruptedException {
        return extract(classBytes, Cancellation.none());
    }

    Extraction extract(byte[] classBytes, Cancellation cancellation) throws InterruptedException {
        cancellation.throwIfCancelled();
        var classModel = classFile.parse(classBytes);
        String callerClass = classModel.thisClass().asInternalName().replace('/', '.');
        List<CallEdge> edges = new ArrayList<>();
        long encounterOrder = 0;
        for (var method : classModel.methods()) {
            cancellation.throwIfCancelled();
            if (method.code().isEmpty()) {
                continue;
            }
            String callerMethod = method.methodName().stringValue();
            String callerDescriptor = method.methodType().stringValue();
            Integer line = null;
            for (CodeElement element : method.code().orElseThrow()) {
                cancellation.throwIfCancelled();
                if (element instanceof LineNumber lineNumber) {
                    line = lineNumber.line();
                }
                else if (element instanceof InvokeInstruction instruction && ORDINARY_OPCODES.contains(instruction.opcode())) {
                    edges.add(new CallEdge(callerClass, callerMethod, callerDescriptor, instruction.owner().asInternalName().replace('/', '.'), instruction.name().stringValue(), instruction.type().stringValue(), line, encounterOrder++));
                }
                else if (element instanceof InvokeDynamicInstruction instruction) {
                    DynamicTarget target = dynamicTarget(instruction);
                    if (target != null) {
                        edges.add(new CallEdge(callerClass, callerMethod, callerDescriptor, target.owner(), target.name(), target.descriptor(), line, encounterOrder++));
                    }
                }
            }
        }
        return new Extraction(callerClass, classModel.methods().size(), List.copyOf(edges));
    }

    record Extraction(String className, int methodCount, List<CallEdge> edges) {
    }

    private record DynamicTarget(String owner, String name, String descriptor) {
    }
}