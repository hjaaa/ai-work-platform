package com.aiwork.baas.ddl.inspect;

/**
 * 物理结构基线准入谓词(spec §9.4):对账、导入及重试探测共用。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class PhysicalStructureAdmission {

    private static final String REQUIRED_CHARSET = "utf8mb4";

    private static final String REQUIRED_COLLATION = "utf8mb4_general_ci";

    private PhysicalStructureAdmission() {
    }

    public static boolean hasRequiredBaseline(PhysicalTable table) {
        return table != null && "BASE TABLE".equals(table.tableType()) && "InnoDB".equals(table.engine())
                && "Dynamic".equals(table.rowFormat()) && REQUIRED_COLLATION.equals(table.collation())
                && !table.hasTriggers() && !table.hasForeignKeys() && !table.hasCheckConstraints()
                && table.columns().stream().allMatch(PhysicalStructureAdmission::hasRequiredColumnBaseline);
    }

    /** varchar/text 列必须维持 utf8mb4 与 utf8mb4_general_ci 基线。 */
    public static boolean hasRequiredColumnBaseline(PhysicalColumn column) {
        if (!"varchar".equalsIgnoreCase(column.dataType()) && !"text".equalsIgnoreCase(column.dataType())) {
            return true;
        }
        return REQUIRED_CHARSET.equals(column.characterSet()) && REQUIRED_COLLATION.equals(column.collation());
    }

}
