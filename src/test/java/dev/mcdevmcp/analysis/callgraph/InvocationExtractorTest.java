package dev.mcdevmcp.analysis.callgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.constant.*;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvocationExtractorTest {
    @TempDir
    Path temporaryDirectory;

    private static void assertEdge(List<CallEdge> edges, String owner, String name, String descriptor) {
        assertTrue(edges.stream().anyMatch(edge -> edge.calleeClass().equals(owner) && edge.calleeMethod().equals(name) && edge.calleeDescriptor().equals(descriptor)), owner + "." + name + descriptor);
    }

    private static void assertDynamic(List<CallEdge> edges, String caller, String owner, String name) {
        assertTrue(edges.stream().anyMatch(edge -> edge.callerMethod().equals(caller) && edge.calleeClass().equals(owner) && edge.calleeMethod().equals(name) && edge.calleeDescriptor().equals("()V")), caller + " -> " + owner + "." + name + "()V");
    }

    @Test
    void exposesTheFourOrdinaryInvocationOpcodes() {
        assertEquals(4, InvocationExtractor.ordinaryOpcodes().size());
        assertTrue(InvocationExtractor.ordinaryOpcodes().contains(Opcode.INVOKEVIRTUAL));
        assertTrue(InvocationExtractor.ordinaryOpcodes().contains(Opcode.INVOKEINTERFACE));
        assertTrue(InvocationExtractor.ordinaryOpcodes().contains(Opcode.INVOKESTATIC));
        assertTrue(InvocationExtractor.ordinaryOpcodes().contains(Opcode.INVOKESPECIAL));
    }

    @Test
    void extractsOrdinaryCallsConstructorsCanonicalDescriptorsLinesAndMultiplicity() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory);
        List<CallEdge> edges = new InvocationExtractor().extract(fixture.bytes("callgraph.fixture.Fixture")).edges();
        List<CallEdge> ordinary = edges.stream().filter(edge -> edge.callerMethod().equals("ordinary")).toList();

        assertEdge(ordinary, "callgraph.fixture.Target", "virtualTarget", "()V");
        assertEdge(ordinary, "callgraph.fixture.Contract", "interfaceTarget", "()V");
        assertEdge(ordinary, "callgraph.fixture.Fixture", "staticTarget", "(I)V");
        assertEdge(ordinary, "callgraph.fixture.Base", "specialTarget", "()V");
        assertEdge(ordinary, "callgraph.fixture.Target", "<init>", "()V");
        assertEdge(ordinary, "callgraph.fixture.Target", "overloaded", "()V");
        assertEdge(ordinary, "callgraph.fixture.Target", "overloaded", "(I)V");
        assertTrue(ordinary.stream().allMatch(edge -> edge.callerDescriptor().equals("(Lcallgraph/fixture/Contract;Lcallgraph/fixture/Target;)V")));
        assertTrue(ordinary.stream().allMatch(edge -> edge.lineNumber() != null && edge.lineNumber() > 0));

        List<CallEdge> sameLine = edges.stream().filter(edge -> edge.callerMethod().equals("duplicateSameLine")).toList();
        assertEquals(2, sameLine.size());
        assertEquals(sameLine.getFirst().lineNumber(), sameLine.getLast().lineNumber());
        List<CallEdge> differentLines = edges.stream().filter(edge -> edge.callerMethod().equals("duplicateDifferentLines")).toList();
        assertEquals(2, differentLines.size());
        assertNotEquals(differentLines.getFirst().lineNumber(), differentLines.getLast().lineNumber());
        assertEquals(edges.size(), edges.stream().map(CallEdge::encounterOrder).distinct().count());
    }

    @Test
    void extractsLambdaAndMethodReferenceTargetsWithLookupDescriptorsAndIgnoresConcatenation() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory);
        List<CallEdge> edges = new InvocationExtractor().extract(fixture.bytes("callgraph.fixture.Fixture")).edges();

        assertDynamic(edges, "constructorReference", "callgraph.fixture.Target", "<init>");
        assertDynamic(edges, "unboundReference", "callgraph.fixture.Target", "virtualTarget");
        assertDynamic(edges, "boundReference", "callgraph.fixture.Target", "virtualTarget");
        assertDynamic(edges, "interfaceReference", "callgraph.fixture.Contract", "interfaceTarget");
        assertTrue(edges.stream().anyMatch(edge -> edge.callerMethod().equals("lambda") && edge.calleeMethod().startsWith("lambda$lambda$")));
        assertTrue(edges.stream().anyMatch(edge -> edge.callerMethod().equals("serializableLambda") && edge.calleeMethod().startsWith("lambda$serializableLambda$")));
        assertFalse(edges.stream().anyMatch(edge -> edge.callerMethod().equals("concatenation")));
    }

    @Test
    void emitsNullLinesWhenTheClassHasNoLineNumberTable() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory);
        List<CallEdge> edges = new InvocationExtractor().extract(fixture.bytes("callgraph.fixture.NoLines")).edges();

        assertEquals(2, edges.size());
        assertTrue(edges.stream().allMatch(edge -> edge.lineNumber() == null));
    }

    @Test
    void ignoresUnknownMalformedAndFieldHandleDynamicSites() throws Exception {
        ClassDesc site = ClassDesc.of("callgraph.dynamic.NegativeSites");
        ClassDesc target = ClassDesc.of("callgraph.dynamic.Target");
        DirectMethodHandleDesc implementation = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, target, "target", ConstantDescs.MTD_void);
        DirectMethodHandleDesc unknownBootstrap = ConstantDescs.ofCallsiteBootstrap(ClassDesc.of("callgraph.dynamic.UnknownBootstrap"), "bootstrap", ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandle);
        DirectMethodHandleDesc lambdaBootstrap = ConstantDescs.ofCallsiteBootstrap(ClassDesc.of("java.lang.invoke.LambdaMetafactory"), "metafactory", ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
        DirectMethodHandleDesc fieldHandle = MethodHandleDesc.ofField(DirectMethodHandleDesc.Kind.STATIC_GETTER, target, "value", ConstantDescs.CD_int);
        DynamicCallSiteDesc unknown = DynamicCallSiteDesc.of(unknownBootstrap, "unknown", ConstantDescs.MTD_void, implementation);
        DynamicCallSiteDesc malformed = DynamicCallSiteDesc.of(lambdaBootstrap, "malformed", ConstantDescs.MTD_void, ConstantDescs.MTD_void, "not-a-handle", ConstantDescs.MTD_void);
        DynamicCallSiteDesc field = DynamicCallSiteDesc.of(lambdaBootstrap, "field", ConstantDescs.MTD_void, ConstantDescs.MTD_void, fieldHandle, ConstantDescs.MTD_void);
        byte[] bytes = ClassFile.of().build(site, classBuilder -> classBuilder.withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.invokedynamic(unknown).invokedynamic(malformed).invokedynamic(field).return_()));

        assertTrue(new InvocationExtractor().extract(bytes).edges().isEmpty());
    }
}
