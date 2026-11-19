package org.windy.playershelter.adapter.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移 SQL 的方言静态自检——不连数据库，只审 {@link Migrations#stepsFor} 生成的 SQL 文本。
 *
 * <p>SQLite 集成测试全绿也挡不住 MySQL 专属的语法问题（如 MySQL 8 不认 {@code CREATE INDEX IF NOT EXISTS}，
 * 会在启动阶段把插件打死）。这里把「MySQL 不认的东西不许出现在 MySQL 分支里」固化成断言。
 */
class MigrationDialectTest {

    /** 收集一种方言全部版本的建表语句（索引/列走 ensureIndex/ensureColumn，不在 SQL 列表里）。 */
    private static List<String> createTableStatements(boolean sqlite) {
        List<String> out = new ArrayList<>();
        for (Migrations.Step step : Migrations.stepsFor(sqlite)) {
            out.addAll(step.statements());
        }
        return out;
    }

    @Test
    @DisplayName("SQL 列表里不再出现 CREATE INDEX / ALTER TABLE（统一走 Java 侧存在性判断）")
    void noDialectOnlyDdlInStatements() {
        for (boolean sqlite : new boolean[]{true, false}) {
            for (String sql : createTableStatements(sqlite)) {
                String upper = sql.toUpperCase();
                assertFalse(upper.contains("CREATE INDEX"), "索引应走 ensureIndex: " + sql);
                assertFalse(upper.startsWith("ALTER TABLE"), "加列应走 ensureColumn: " + sql);
            }
        }
    }

    @Test
    @DisplayName("索引仍被完整声明，且名字唯一（存在性检查靠索引名）")
    void indicesDeclaredAndUnique() {
        List<Migrations.Index> indices = new ArrayList<>();
        for (Migrations.Step step : Migrations.stepsFor(true)) {
            indices.addAll(step.indices());
        }
        assertFalse(indices.isEmpty(), "索引声明不能丢");
        for (Migrations.Index index : indices) {
            long same = indices.stream().filter(i -> i.name().equals(index.name())).count();
            assertTrue(same == 1, "索引名重复: " + index.name());
        }
    }

    @Test
    @DisplayName("MySQL 建表钉 utf8mb4，SQLite 不带字符集子句")
    void charsetOnlyOnMysql() {
        for (String sql : createTableStatements(false)) {
            assertTrue(sql.toUpperCase().startsWith("CREATE TABLE") && sql.contains("utf8mb4"),
                    "MySQL 建表应钉 utf8mb4: " + sql);
        }
        for (String sql : createTableStatements(true)) {
            assertFalse(sql.toUpperCase().contains("UTF8MB4"), "SQLite 建表不该有字符集子句: " + sql);
        }
    }

    @Test
    @DisplayName("两端迁移版本集合一致")
    void versionsMatchAcrossDialects() {
        List<Migrations.Step> sqlite = Migrations.stepsFor(true);
        List<Migrations.Step> mysql = Migrations.stepsFor(false);
        assertEquals(sqlite.size(), mysql.size(), "两端迁移步数应一致");
        for (int i = 0; i < sqlite.size(); i++) {
            assertEquals(sqlite.get(i).version(), mysql.get(i).version(), "第 " + i + " 步版本号不一致");
        }
    }

    @Test
    @DisplayName("类型方言：MySQL 用 BIGINT/AUTO_INCREMENT，SQLite 用 INTEGER/AUTOINCREMENT")
    void typeDialects() {
        String mysqlMessage = createTableStatements(false).stream()
                .filter(s -> s.contains("ps_message")).findFirst().orElseThrow();
        assertTrue(mysqlMessage.contains("BIGINT") && mysqlMessage.contains("AUTO_INCREMENT"), mysqlMessage);

        String sqliteMessage = createTableStatements(true).stream()
                .filter(s -> s.contains("ps_message")).findFirst().orElseThrow();
        assertFalse(sqliteMessage.contains("BIGINT"), sqliteMessage);
        assertTrue(sqliteMessage.contains("AUTOINCREMENT"), sqliteMessage);
    }
}
