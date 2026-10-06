package com.company.yoga.common.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

public final class UuidUtils {

    private static final TimeBasedEpochGenerator UUID_V7_GENERATOR = Generators.timeBasedEpochGenerator();

    private UuidUtils() {}

    /**
     * Sinh UUIDv7 tuần tự theo thời gian (Epoch-based time UUID).
     * Tối ưu cho B-tree Index trong PostgreSQL, chống phân mảnh chỉ mục.
     */
    public static UUID uuidV7() {
        return UUID_V7_GENERATOR.generate();
    }

    public static String uuidV7String() {
        return uuidV7().toString();
    }
}
