[#list ["A", "B", "C"] as addedValue]
INSERT INTO LOOM_DB_TEST_TABLE (COL1) VALUES ('${addedValue}');
[/#list]
