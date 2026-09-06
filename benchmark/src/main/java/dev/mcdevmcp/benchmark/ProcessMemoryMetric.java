package dev.mcdevmcp.benchmark;

/**
 * Platform-specific process-lifetime resident-memory high-water mark.
 */
public enum ProcessMemoryMetric {
    LINUX_VM_HWM, WINDOWS_PEAK_WORKING_SET, UNAVAILABLE
}