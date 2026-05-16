package com.w3n.webstream;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

public final class ProcessorInfo {
    public static final String TAG_PROCESSOR_NAME = "PROCESSOR_NAME";
    private static final int LOG_CHUNK_SIZE = 3000;
    private static final String CPU_INFO_PATH = "/proc/cpuinfo";
    private static final String UNKNOWN_PROCESSOR = "Unknown processor";

    private ProcessorInfo() {
    }

    public static String getProcessorName() {
        String processorName = getProcessorNameFromBuild();
        if (!processorName.isEmpty()) {
            return processorName;
        }

        processorName = getProcessorNameFromCpuInfo();
        if (!processorName.isEmpty()) {
            return processorName;
        }

        return UNKNOWN_PROCESSOR;
    }

    public static String getProcessorNameFromBuild() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String socName = combine(Build.SOC_MANUFACTURER, Build.SOC_MODEL);
            if (!socName.isEmpty()) {
                return socName;
            }
        }

        return sanitize(Build.HARDWARE);
    }

    public static String getProcessorNameFromCpuInfo() {
        try (BufferedReader reader = new BufferedReader(new FileReader(CPU_INFO_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = getCpuInfoValue(line, "Hardware");
                if (!value.isEmpty()) {
                    return value;
                }

                value = getCpuInfoValue(line, "model name");
                if (!value.isEmpty()) {
                    return value;
                }

                value = getCpuInfoValue(line, "Processor");
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException ignored) {
            return "";
        }

        return "";
    }

    public static String getCpuInfoContent() {
        StringBuilder cpuInfo = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(CPU_INFO_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                cpuInfo.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return "";
        }

        return cpuInfo.toString();
    }

    public static void logCpuInfoContent() {
        String cpuInfoContent = getCpuInfoContent();
        if (cpuInfoContent.isEmpty()) {
            Log.d(TAG_PROCESSOR_NAME, "/proc/cpuinfo is empty or unavailable");
            return;
        }

        for (int start = 0; start < cpuInfoContent.length(); start += LOG_CHUNK_SIZE) {
            int end = Math.min(start + LOG_CHUNK_SIZE, cpuInfoContent.length());
            Log.d(TAG_PROCESSOR_NAME, "/proc/cpuinfo:\n" + cpuInfoContent.substring(start, end));
        }
    }

    private static String getCpuInfoValue(String line, String key) {
        int separatorIndex = line.indexOf(':');
        if (separatorIndex == -1) {
            return "";
        }

        String foundKey = line.substring(0, separatorIndex).trim();
        if (!isCpuInfoKeyMatch(foundKey, key)) {
            return "";
        }

        String value = sanitize(line.substring(separatorIndex + 1));
        return isCpuInfoProcessorCoreIndex(foundKey, value) ? "" : value;
    }

    private static boolean isCpuInfoKeyMatch(String foundKey, String expectedKey) {
        if ("Processor".equals(expectedKey)) {
            return foundKey.equals(expectedKey);
        }
        return foundKey.equalsIgnoreCase(expectedKey);
    }

    private static boolean isCpuInfoProcessorCoreIndex(String key, String value) {
        return "processor".equalsIgnoreCase(key) && value.matches("\\d+");
    }

    private static String combine(String firstValue, String secondValue) {
        String first = sanitize(firstValue);
        String second = sanitize(secondValue);

        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        if (second.toLowerCase(Locale.US).contains(first.toLowerCase(Locale.US))) {
            return second;
        }

        return first + " " + second;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
