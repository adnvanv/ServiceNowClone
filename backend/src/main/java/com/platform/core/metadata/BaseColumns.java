package com.platform.core.metadata;

import java.util.List;
import java.util.Set;

/**
 * The audit/identity columns every user-created table carries. They are not
 * rows in sys_dictionary — they exist implicitly on every table, which is why
 * query validation and field projection special-case them.
 */
public final class BaseColumns {

    public static final String SYS_ID = "sys_id";
    public static final String SYS_CREATED_ON = "sys_created_on";
    public static final String SYS_CREATED_BY = "sys_created_by";
    public static final String SYS_UPDATED_ON = "sys_updated_on";
    public static final String SYS_UPDATED_BY = "sys_updated_by";
    public static final String SYS_MOD_COUNT = "sys_mod_count";

    /**
     * Present on root tables only: the actual class of the row, so that a
     * query against a parent table can report which child each row belongs
     * to. Stored rather than computed — see the inheritance ADR.
     */
    public static final String SYS_CLASS_NAME = "sys_class_name";

    public static final Set<String> ALL = Set.of(
            SYS_ID, SYS_CREATED_ON, SYS_CREATED_BY, SYS_UPDATED_ON, SYS_UPDATED_BY,
            SYS_MOD_COUNT, SYS_CLASS_NAME);

    /** Columns that exist physically on a root table, in DDL order. */
    public static final List<String> ROOT_DDL = List.of(
            SYS_ID, SYS_CREATED_ON, SYS_CREATED_BY, SYS_UPDATED_ON, SYS_UPDATED_BY,
            SYS_MOD_COUNT, SYS_CLASS_NAME);

    public static boolean isBase(String element) {
        return ALL.contains(element);
    }

    private BaseColumns() {
    }
}
