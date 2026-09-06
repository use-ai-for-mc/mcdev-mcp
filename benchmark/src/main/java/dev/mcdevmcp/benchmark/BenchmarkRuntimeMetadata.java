package dev.mcdevmcp.benchmark;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;

/**
 * Runtime identity captured inside each measurement JVM.
 */
public record BenchmarkRuntimeMetadata(int javaFeature, String vendor, String javaVersion, String runtimeVersion, String vmName, String vmVersion, String vmFlags, List<String> garbageCollectors, String osName, ProcessMemoryMetric memoryMetric) {
    static final int REQUIRED_JAVA_FEATURE = 26;

    public BenchmarkRuntimeMetadata {
        vendor = Objects.requireNonNull(vendor, "vendor");
        javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        vmName = Objects.requireNonNull(vmName, "vmName");
        vmVersion = Objects.requireNonNull(vmVersion, "vmVersion");
        vmFlags = Objects.requireNonNull(vmFlags, "vmFlags");
        garbageCollectors = List.copyOf(garbageCollectors);
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(memoryMetric, "memoryMetric");
    }

    public static BenchmarkRuntimeMetadata current() {
        int feature = Runtime.version().feature();
        if (feature != REQUIRED_JAVA_FEATURE) {
            throw new IllegalStateException("Java 26 is required for the blocking benchmark; detected Java " + feature);
        }
        return new BenchmarkRuntimeMetadata(feature, System.getProperty("java.vendor"), System.getProperty("java.version"), Runtime.version().toString(), System.getProperty("java.vm.name"), System.getProperty("java.vm.version"), String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()), ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.MemoryManagerMXBean::getName).sorted().toList(), System.getProperty("os.name"), ProcessPeakMemory.metric());
    }
}
