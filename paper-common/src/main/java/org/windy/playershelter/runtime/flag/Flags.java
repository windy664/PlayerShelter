package org.windy.playershelter.runtime.flag;

import org.windy.playershelter.domain.model.Shelter;

/**
 * flag 取值工具：从 {@link Shelter#flags()}（id→"true"/"false"）解析布尔，缺省回退 {@link Flag#defaultValue()}。
 */
public final class Flags {

    private Flags() {
    }

    /** 该庇护所某 flag 当前是否开启（未显式设置取默认值）。 */
    public static boolean isOn(Shelter shelter, Flag flag) {
        String v = shelter.flags().get(flag.id());
        if (v == null) {
            return flag.defaultValue();
        }
        return Boolean.parseBoolean(v.trim());
    }
}
