package com.aiwork.baas.data.meta;

import java.util.Locale;

/**
 * 数据面四操作(ACL 开关维度,spec §8.2)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public enum DataOperation {

    /** 查询数据。 */
    SELECT,
    /** 新增数据。 */
    INSERT,
    /** 更新数据。 */
    UPDATE,
    /** 删除数据。 */
    DELETE;

    /**
     * 获取 ACL 字段使用的小写操作标记。
     *
     * @return 小写操作标记
     */
    public String aclLabel() {
        return name().toLowerCase(Locale.ROOT);
    }

}
